package hainanMahjong.service.impl;

import hainanMahjong.monitoring.CapacityMetrics;
import hainanMahjong.repository.RoomSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One small poller, no in-memory backlog and no extra datasource/broker. Single JVM only. */
@Component
public class RoomCloseOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(RoomCloseOutboxWorker.class);
    private final RoomCloseOutbox outbox;
    private final RoomSnapshotRepository snapshots;
    private final CapacityMetrics metrics;
    private final boolean enabled;
    private ScheduledExecutorService executor;
    private int ticks;
    private long nextFailureLog;

    public RoomCloseOutboxWorker(RoomCloseOutbox outbox, RoomSnapshotRepository snapshots,
            CapacityMetrics metrics, @Value("${room-close-outbox.worker-enabled:true}") boolean enabled) {
        this.outbox = outbox; this.snapshots = snapshots; this.metrics = metrics; this.enabled = enabled;
        metrics.gauge("mahjong.outbox.worker.enabled", enabled ? 1 : 0);
        for (String name : new String[]{"pending", "dead", "oldest.seconds"})
            metrics.gauge("mahjong.outbox." + name, 0);
        metrics.count("mahjong.outbox.worker.initialized");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-close-outbox"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    void poll() {
        try {
            for (RoomCloseOutbox.Job job : outbox.due()) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    // false means another generation owns the code: old cleanup is obsolete.
                    snapshots.closeRoom(job.code(), job.generation());
                    outbox.complete(job.sessionId(), job.generation());
                    metrics.count("mahjong.outbox.completed");
                } catch (Exception e) {
                    // Do not persist exception messages, which can contain credentials/endpoints.
                    outbox.fail(job, e.getClass().getSimpleName());
                    metrics.count("mahjong.outbox.retries");
                    if (job.attempts() >= 11)
                        log.error("Outbox exhausted id={} session={} generation={}", job.id(), job.sessionId(), job.generation());
                }
            }
            if (ticks++ % 30 == 0) {
                RoomCloseOutbox.Stats stats = outbox.stats();
                metrics.gauge("mahjong.outbox.pending", stats.pending());
                metrics.gauge("mahjong.outbox.dead", stats.dead());
                metrics.gauge("mahjong.outbox.oldest.seconds", stats.oldestSeconds());
                outbox.prune();
            }
            metrics.gauge("mahjong.outbox.poll.healthy", 1);
        } catch (Exception e) {
            metrics.gauge("mahjong.outbox.poll.healthy", 0);
            metrics.count("mahjong.outbox.poll.failures");
            long now = System.nanoTime();
            if (now >= nextFailureLog) {
                log.error("Room close outbox poll failed ({})", e.getClass().getSimpleName());
                nextFailureLog = now + TimeUnit.SECONDS.toNanos(60);
            }
        }
    }

    @PreDestroy public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
    }
}
