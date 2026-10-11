package hainanMahjong.monitoring;

import hainanMahjong.room.*;
import hainanMahjong.engine.model.Seat;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 聚合指标，不以玩家、房间码或令牌作为标签。 */
@Component
public class CapacityMetrics {
    private final MeterRegistry meters;
    private final RoomRegistry rooms;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();
    private final Map<String, String> connections = new ConcurrentHashMap<>();
    private final Map<Long, Delivery> deliveries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong pendingSends = new AtomicLong();

    public CapacityMetrics(MeterRegistry meters, RoomRegistry rooms) {
        this.meters = meters; this.rooms = rooms;
        Gauge.builder("mahjong.ws.send.pending", pendingSends, AtomicLong::get).register(meters);
        Gauge.builder("mahjong.ws.delivery.pending", deliveries, Map::size).register(meters);
        for (String endpoint : List.of("room", "game")) {
            Gauge.builder("mahjong.ws.connections", connections,
                    map -> map.values().stream().filter(endpoint::equals).count()).tag("endpoint", endpoint).register(meters);
        }
    }
    public void gauge(String name, long value) {
        values.computeIfAbsent(name, key -> {
            AtomicLong number = new AtomicLong(); Gauge.builder(key, number, AtomicLong::get).register(meters); return number;
        }).set(value);
    }
    public void count(String name, String... tags) { meters.counter(name, tags).increment(); }
    public void time(String name, long nanos, String... tags) {
        io.micrometer.core.instrument.Timer.builder(name).tags(tags).publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                .register(meters).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }
    public void bytes(String name, long bytes, String... tags) {
        DistributionSummary.builder(name).tags(tags).baseUnit("bytes").publishPercentileHistogram()
                .maximumExpectedValue(16 * 1024 * 1024.0).register(meters).record(bytes);
    }
    public void connected(WebSocketSession ws, String endpoint) {
        connections.put(ws.getId(), endpoint); count("mahjong.ws.opened", "endpoint", endpoint);
    }
    public void disconnected(WebSocketSession ws, String endpoint) {
        if (connections.remove(ws.getId()) != null) count("mahjong.ws.closed", "endpoint", endpoint);
    }
    public void sending() { pendingSends.incrementAndGet(); }
    public void sent(long started, long size, String outcome) {
        pendingSends.decrementAndGet(); time("mahjong.ws.send", System.nanoTime() - started, "outcome", outcome);
        bytes("mahjong.ws.message", size, "outcome", outcome);
    }
    /** 只跟踪四名在线真人的关键广播；确认往返是送达延迟的上界，不能称单向延迟。 */
    public synchronized long delivery(Room room, Map<String, Object> message) {
        String type = String.valueOf(message.get("type"));
        if (!Set.of("hand_start", "coins", "end", "match").contains(type)) return 0;
        Set<String> audience = new HashSet<>();
        for (Seat seat : Seat.values()) {
            Member m = room.members.get(seat);
            if (m != null && m.userId > 0 && m.gameWs != null && m.gameWs.isOpen()) audience.add(m.gameWs.getId());
        }
        if (audience.size() != 4) return 0;
        if (deliveries.size() >= 2048) { count("mahjong.ws.delivery.dropped"); return 0; }
        long id = sequence.incrementAndGet();
        deliveries.put(id, new Delivery(room.generation, type, audience));
        count("mahjong.ws.delivery.expected", "event", type); return id;
    }
    public synchronized void acknowledge(long id, WebSocketSession ws, Room room) {
        Delivery delivery = deliveries.get(id);
        if (delivery == null || !delivery.generation.equals(room.generation)) return;
        if (!delivery.audience.remove(ws.getId())) return;
        if (delivery.audience.isEmpty()) {
            deliveries.remove(id); time("mahjong.ws.delivery.confirmation", System.nanoTime() - delivery.started, "event", delivery.type);
        }
    }
    public void sampleRooms() {
        long playing = 0, waiting = 0, between = 0, occupied = 0, timers = 0;
        Set<Long> active = new HashSet<>(), online = new HashSet<>();
        for (Room room : rooms.allRooms()) {
            if (room.state == State.CLOSED) continue;
            hainanMahjong.engine.RoomManager engine = room.active;
            if (engine != null) timers += engine.pendingTimerCount();
            if (room.state == State.PLAYING) playing++;
            else if (room.state == State.WAITING) waiting++;
            else if (room.state == State.BETWEEN) between++;
            // 指标是采样近似值，不在采集线程获取房间锁或阻塞对局。
            for (Seat seat : Seat.values()) {
                Member m = room.members.get(seat);
                if (m == null || m.userId <= 0) continue;
                occupied++;
                WebSocketSession game = m.gameWs, lobby = m.roomWs;
                if ((game != null && game.isOpen()) || (lobby != null && lobby.isOpen())) online.add(m.userId);
                if (room.state == State.PLAYING && !m.offline && game != null && game.isOpen()) active.add(m.userId);
            }
        }
        gauge("mahjong.rooms.playing", playing); gauge("mahjong.rooms.waiting", waiting); gauge("mahjong.rooms.between", between);
        gauge("mahjong.players.active", active.size()); gauge("mahjong.players.online", online.size());
        gauge("mahjong.players.occupied", occupied); gauge("mahjong.players.idle", Math.max(0, online.size() - active.size()));
        gauge("mahjong.engine.timer.pending", timers);
        synchronized (this) {
            long now = System.nanoTime();
            deliveries.entrySet().removeIf(entry -> {
                Delivery d = entry.getValue();
                if (now - d.started < TimeUnit.SECONDS.toNanos(15)) return false;
                count("mahjong.ws.delivery.expired", "event", d.type); return true;
            });
        }
    }
    private static class Delivery {
        final String generation, type;
        final Set<String> audience;
        final long started = System.nanoTime();
        Delivery(String generation, String type, Set<String> audience) { this.generation = generation; this.type = type; this.audience = audience; }
    }
}
