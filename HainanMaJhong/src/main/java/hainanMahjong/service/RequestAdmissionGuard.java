package hainanMahjong.service;

import hainanMahjong.config.TrafficProtectionProperties;
import hainanMahjong.config.TrafficProtectionProperties.Policy;
import hainanMahjong.config.TrafficProtectionProperties.Rate;
import hainanMahjong.exception.AdmissionRejectedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Non-blocking local admission: no sleeps, no unbounded queues, no Redis round-trips. */
@Component
public class RequestAdmissionGuard {
    private static final Pattern NUMERIC_IP = Pattern.compile("[0-9a-fA-F:.]+");
    private final boolean enabled;
    private final int busyRetrySeconds;
    private final Set<String> trustedProxies = new HashSet<>();
    private final Group login;
    private final Group records;
    private final Group legacy;

    public RequestAdmissionGuard(TrafficProtectionProperties config, MeterRegistry meters) {
        enabled = config.isEnabled();
        busyRetrySeconds = config.getBusyRetrySeconds();
        for (String address : config.getTrustedProxies()) {
            String normalized = numericAddress(address);
            if (normalized == null) throw new IllegalArgumentException("trusted-proxies must contain numeric IP addresses");
            trustedProxies.add(normalized);
        }
        login = new Group("login", config.getLogin(), config, meters);
        records = new Group("records", config.getRecords(), config, meters);
        legacy = new Group("legacy", config.getLegacy(), config, meters);
    }

    /** Called before authentication/password hashing; all login attempts consume the budget. */
    public Permit login(String account, HttpServletRequest request) {
        if (!enabled) return new Permit(() -> {});
        return login.acquire(account.trim().toLowerCase(Locale.ROOT), clientAddress(request));
    }

    /** Called only after JWT and authorization interceptors have accepted the request. */
    public Permit records(long userId, HttpServletRequest request, boolean legacyEndpoint) {
        if (!enabled) return new Permit(() -> {});
        String user = Long.toString(userId), ip = clientAddress(request);
        // Reject legacy-specific overload before spending the shared V2 query budget.
        Permit extra = legacyEndpoint ? legacy.acquire(user, ip) : null;
        try {
            Permit common = records.acquire(user, ip);
            return new Permit(() -> {
                if (extra != null) extra.close();
                common.close();
            });
        } catch (RuntimeException e) {
            if (extra != null) extra.close();
            throw e;
        }
    }

    /** Idempotent release, including exception and duplicate completion paths. */
    public static final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;
        private Permit(Runnable release) { this.release = release; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
    }

    private final class Group {
        private final Semaphore permits;
        private final Bucket global;
        private final KeyBudgets principals;
        private final KeyBudgets ips;
        private final Map<String, Counter> counters = new HashMap<>();

        Group(String name, Policy policy, TrafficProtectionProperties config, MeterRegistry meters) {
            permits = new Semaphore(policy.getConcurrency());
            global = new Bucket(policy.getGlobal(), System.nanoTime());
            principals = new KeyBudgets(policy.getPrincipal(), config);
            ips = new KeyBudgets(policy.getIp(), config);
            for (String outcome : new String[]{"admitted", "concurrency", "global_rate", "principal_rate", "ip_rate", "key_capacity"})
                counters.put(outcome, meters.counter("mahjong.admission.requests", "group", name, "outcome", outcome));
            Gauge.builder("mahjong.admission.inflight", permits,
                    semaphore -> policy.getConcurrency() - semaphore.availablePermits()).tag("group", name).register(meters);
            Gauge.builder("mahjong.admission.keys", principals, KeyBudgets::size)
                    .tag("group", name).tag("scope", "principal").register(meters);
            Gauge.builder("mahjong.admission.keys", ips, KeyBudgets::size)
                    .tag("group", name).tag("scope", "ip").register(meters);
        }

        Permit acquire(String principal, String ip) {
            if (!permits.tryAcquire()) throw rejected("concurrency", busyRetrySeconds);
            try {
                long now = System.nanoTime();
                check(ips.consume(ip, now), "ip_rate");
                check(principals.consume(principal, now), "principal_rate");
                // A caller already over its own quota cannot drain everyone else's global budget.
                long wait = global.consume(now);
                if (wait > 0) throw rejected("global_rate", wait);
                counters.get("admitted").increment();
                return new Permit(permits::release);
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
        }

        private void check(long wait, String reason) {
            if (wait == -1) throw rejected("key_capacity", busyRetrySeconds);
            if (wait > 0) throw rejected(reason, wait);
        }

        private AdmissionRejectedException rejected(String reason, long wait) {
            counters.get(reason).increment();
            return new AdmissionRejectedException((int) Math.max(1, Math.min(3600, wait)));
        }
    }

    private static final class Bucket {
        private final double rate;
        private final int capacity;
        private double tokens;
        private long updated;
        private long seen;

        Bucket(Rate config, long now) {
            rate = config.getPerSecond(); capacity = config.getBurst();
            tokens = capacity; updated = now; seen = now;
        }

        private void refill(long now) {
            long elapsed = now - updated;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * rate);
                updated = now;
            }
        }

        synchronized long consume(long now) {
            refill(now);
            if (now - seen > 0) seen = now;
            if (tokens >= 1) { tokens -= 1; return 0; }
            return Math.max(1, (long) Math.ceil((1 - tokens) / rate));
        }

        synchronized boolean expires(long now, long idleNanos) {
            refill(now);
            // Never evict depleted buckets: rotating keys must not reset their quota.
            return now - seen >= idleNanos && tokens >= capacity;
        }
    }

    /** Bounded access-order maps; active entries are never evicted to admit new identities. */
    private static final class KeyBudgets {
        private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);
        private final Rate rate;
        private final int maximum;
        private final long idleNanos;

        KeyBudgets(Rate rate, TrafficProtectionProperties config) {
            this.rate = rate; maximum = config.getMaxKeys();
            idleNanos = TimeUnit.SECONDS.toNanos(config.getIdleSeconds());
        }

        synchronized long consume(String key, long now) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                // Same refill policy per map: later entries cannot expire before the oldest.
                var entries = buckets.entrySet().iterator();
                for (int i = 0; i < 16 && entries.hasNext(); i++) {
                    if (!entries.next().getValue().expires(now, idleNanos)) break;
                    entries.remove();
                }
                if (buckets.size() >= maximum) return -1;
                bucket = new Bucket(rate, now);
                buckets.put(key, bucket);
            }
            return bucket.consume(now);
        }

        synchronized int size() { return buckets.size(); }
    }

    private String clientAddress(HttpServletRequest request) {
        // Boot's forwarded-header wrapper may already have replaced getRemoteAddr().
        // Inspect the actual servlet peer before deciding whether to trust any headers.
        ServletRequest original = request;
        while (original instanceof ServletRequestWrapper wrapper) original = wrapper.getRequest();
        String peer = numericAddress(original.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trustedProxies.contains(peer) || !(original instanceof HttpServletRequest raw)) return peer;
        String forwarded = raw.getHeader("X-Forwarded-For");
        if (forwarded != null && forwarded.length() <= 2048) {
            String[] hops = forwarded.split(",", -1);
            if (hops.length <= 16) {
                // Nginx appends its observed client; ignore a spoofed left-hand prefix.
                for (int i = hops.length - 1; i >= 0; i--) {
                    String address = numericAddress(hops[i].trim());
                    if (address == null) return peer;
                    if (!trustedProxies.contains(address)) return address;
                }
            }
        }
        // Missing/malformed forwarding deliberately shares the proxy's quota.
        return peer;
    }

    private static String numericAddress(String value) {
        if (value == null || value.length() > 45 || !NUMERIC_IP.matcher(value).matches()) return null;
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return null;
            try { for (String part : parts) if (part.isEmpty() || Integer.parseInt(part) > 255) return null; }
            catch (NumberFormatException e) { return null; }
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception e) { return null; } // numeric-only input: never perform hostname resolution
    }
}
