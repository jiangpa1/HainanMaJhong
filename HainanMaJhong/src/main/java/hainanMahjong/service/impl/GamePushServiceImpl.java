package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.service.GamePushService;
import hainanMahjong.websocket.GamePushProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 牌局消息的组装与发送。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>无状态</b>：没有房间缓存、不记成员，所有数据从传入的 {@link Room} 读</li>
 *   <li>广播沿用 {@code room.lock}，与座位绑定及 welcome 顺序一致</li>
 *   <li><b>无 I/O</b>：不碰 MySQL / Redis，只有 WebSocket 发送</li>
 * </ul>
 *
 * <p>{@link #sendJson} 是全项目唯一的 WebSocket 发送出口：它统一处理
 * "session 已关闭"、发送异常，并使用会话发送锁 —— 避免两个线程同时往同一个
 * WebSocket 写帧（原生 WebSocket 的 {@code sendMessage} 不是线程安全的）。</p>
 */
@Service
public class GamePushServiceImpl implements GamePushService {

    private static final Logger log = LoggerFactory.getLogger(GamePushServiceImpl.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private hainanMahjong.monitoring.CapacityMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCapacityMetrics(hainanMahjong.monitoring.CapacityMetrics metrics) { this.metrics = metrics; }

    /** 向房间内所有真人的牌局连接广播（机器人跳过；连接未打开的跳过）。 */
    @Override
    public void broadcastGame(Room room, Map<String, Object> msg) {
        room.lock.lock();
        try {
            if (room.stop || room.state == hainanMahjong.room.State.CLOSED) return;
            if (metrics != null) {
                long sequence = metrics.delivery(room, msg);
                if (sequence > 0) {
                    msg = new LinkedHashMap<>(msg);
                    msg.put("_monitorSeq", sequence);
                }
            }
            Encoded payload = null;
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m == null || m.userId < 0 || m.gameWs == null || !m.gameWs.isOpen()) {
                    continue;
                }
                // Direct snapshot sends deliberately bypass this broadcast-only filter.
                if (GamePushProtocol.delta(m.gameWs)
                        && ("counts".equals(msg.get("type")) || "turn".equals(msg.get("type")))) continue;
                if (payload == null) {
                    payload = encodeOrLog(msg);
                    if (payload == null) return;
                }
                sendEncoded(m.gameWs, payload);
            }
        } finally { room.lock.unlock(); }
    }

    /**
     * 按座位发不同内容（暗杠用）。
     *
     * <p>判据是成员的 {@code seat} 而不是 userId：调用方拿到的就是座位枚举，
     * 而"谁该看到真实牌面"这件事本来就是按座位定的。</p>
     *
     * <p>两种内容各自只序列化一次，且只对该次真正出现的变体序列化。</p>
     */
    @Override
    public void broadcastGameMasked(Room room, Seat owner, Map<String, Object> full, Map<String, Object> masked) {
        room.lock.lock();
        try {
            if (room.stop || room.state == hainanMahjong.room.State.CLOSED) return;
            Encoded fullPayload = null, maskedPayload = null;
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m == null || m.userId < 0 || m.gameWs == null || !m.gameWs.isOpen()) {
                    continue;
                }
                Encoded payload;
                if (m.seat == owner) {
                    if (fullPayload == null) {
                        fullPayload = encodeOrLog(full);
                        if (fullPayload == null) return;
                    }
                    payload = fullPayload;
                } else {
                    if (maskedPayload == null) {
                        maskedPayload = encodeOrLog(masked);
                        if (maskedPayload == null) return;
                    }
                    payload = maskedPayload;
                }
                sendEncoded(m.gameWs, payload);
            }
        } finally { room.lock.unlock(); }
    }

    /**
     * 唯一的发送出口（单个收件人）。
     *
     * <p>使用会话发送锁是必须的：原生 WebSocket 的 {@code sendMessage} 不是线程安全的，
     * 而房间线程与 WebSocket 线程可能同时发消息。</p>
     */
    @Override
    public void sendJson(WebSocketSession session, Map<String, Object> msg) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Encoded payload = encodeOrLog(msg);
        if (payload == null) return;
        sendEncoded(session, payload);
    }

    /** 已编码的载荷：同一条消息发给多个连接时复用，避免重复序列化与重复 UTF-8 编码。 */
    private static final class Encoded {
        final String json;
        final int size;
        final String type;
        Encoded(String json, int size, String type) { this.json = json; this.size = size; this.type = type; }
    }

    /** 按消息类型的计数（懒注册 Gauge，热路径只有 CHM 查表 + 原子加法，用于量化"每人一份"的重复量）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> encodedCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> encodedBytes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> sentCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> sentBytes = new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.concurrent.atomic.AtomicLong counter(
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> map, String name, String type) {
        return map.computeIfAbsent(type, key -> metrics.gaugeRef(name, "type", key));
    }

    /** 序列化一次并记录成本；失败只记日志并返回 null（调用方结束本次发送）。 */
    private Encoded encodeOrLog(Map<String, Object> msg) {
        long started = System.nanoTime();
        try {
            String json = mapper.writeValueAsString(msg);
            int size = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (metrics != null) metrics.serialized(System.nanoTime() - started, size);
            String type = String.valueOf(msg.get("type"));
            if (metrics != null && !"null".equals(type)) {
                counter(encodedCount, "mahjong.ws.encoded.count", type).incrementAndGet();
                counter(encodedBytes, "mahjong.ws.encoded.bytes", type).addAndGet(size);
            }
            return new Encoded(json, size, type);
        } catch (Exception e) {
            log.warn("序列化消息失败：{}", e.getMessage());
            return null;
        }
    }

    /** 发送已编码载荷；同一 Encoded 可被多个 session 复用（编码结果不可变）。 */
    private void sendEncoded(WebSocketSession session, Encoded payload) {
        long started = System.nanoTime();
        int wireSize = payload.size;
        String outcome = "closed";
        if (metrics != null) metrics.sending();
        try {
            var guardLock3 = hainanMahjong.websocket.SessionSendLocks.lock(session);
            guardLock3.lock();
            try {
                if (session.isOpen()) {
                    String json = payload.json;
                    if (session.getAttributes().containsKey(GamePushProtocol.MODE)) {
                        // A room-global counter would have legitimate gaps from private messages
                        // and full/delta filtering. Allocate contiguous per-connection seq under
                        // the same lock as the actual write, preserving shared payload encoding.
                        Object previous = session.getAttributes().get(GamePushProtocol.SEQUENCE);
                        long seq = previous instanceof Number n ? n.longValue() + 1 : 1;
                        session.getAttributes().put(GamePushProtocol.SEQUENCE, seq);
                        String suffix = ",\"seq\":" + seq + "}";
                        json = json.substring(0, json.length() - 1) + suffix;
                        wireSize += suffix.length() - 1;
                    }
                    session.sendMessage(new TextMessage(json));
                    outcome = "success";
                }
            } finally { guardLock3.unlock(); }
        } catch (Exception e) {
            outcome = "error";
            log.warn("发送消息失败：{}", e.getMessage());
        } finally {
            if (metrics != null) {
                metrics.sent(started, wireSize, outcome);
                if (metrics != null && payload.type != null && !"null".equals(payload.type)) {
                    counter(sentCount, "mahjong.ws.sent.count", payload.type).incrementAndGet();
                    counter(sentBytes, "mahjong.ws.sent.bytes", payload.type).addAndGet(wireSize);
                }
            }
        }
    }

    /** 房间各座位金币视图；四个座位都有键，缺省 0。 */
    @Override
    public Map<String, Integer> coinView(Room room) {
        Map<String, Integer> view = new LinkedHashMap<>();
        for (Seat s : Seat.values()) {
            view.put(s.name(), room.coins.get(s) == null ? 0 : room.coins.get(s));
        }
        return view;
    }

    /** 副露 → JSON 结构：{type, tiles[], from?}。 */
    @Override
    public Map<String, Object> serializeMeld(Meld meld) {
        return serializeMeld(meld, false);
    }

    /**
     * 副露 → JSON，可隐藏牌面。
     *
     * <p>隐藏时带一个 {@code hidden:true} 标记，{@code tiles} 给 4 个占位值：
     * 前端看到 hidden 就画 4 张牌背、根本不读 tiles，占位值只为了让"长度=张数"
     * 这条不变量继续成立（有些地方会按长度判断副露大小）。</p>
     *
     * <p>暗杠必须走这条路 —— 把 4 张真实牌面广播给全桌等于把暗杠亮给对手看。</p>
     */
    @Override
    public Map<String, Object> serializeMeld(Meld meld, boolean hidden) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", meld.type().name());
        if (meld.from() != null) {
            m.put("from", meld.from().name());
        }
        List<Integer> tiles = new ArrayList<>();
        if (hidden) {
            m.put("hidden", true);
            int n = meld.tiles() == null ? 0 : meld.tiles().length;
            for (int i = 0; i < n; i++) {
                tiles.add(0); // 占位，前端不会读
            }
        } else {
            for (int t : meld.tiles()) {
                tiles.add(t);
            }
        }
        m.put("tiles", tiles);
        return m;
    }

    /** 房间信息：发给指定连接，或发给房内全部等待房连接（内容相同，编码一次复用）。 */
    @Override
    public void sendRoomInfo(Room room, WebSocketSession only) {
        Map<String, Object> msg = roomInfoMsg(room);
        if (only != null) {
            sendJson(only, msg);
        } else {
            Encoded payload = null;
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m.roomWs != null && m.roomWs.isOpen()) {
                    if (payload == null) {
                        payload = encodeOrLog(msg);
                        if (payload == null) return;
                    }
                    sendEncoded(m.roomWs, payload);
                }
            }
        }
    }

    /** 组装 room_info 消息体：房间码、房主、状态、成员座位表。 */
    @Override
    public Map<String, Object> roomInfoMsg(Room room) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "room_info");
        msg.put("code", room.code);
        msg.put("hostUserId", room.hostUserId);
        msg.put("state", room.state.name());
        List<Map<String, Object>> members = new ArrayList<>();
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m == null) {
                continue;
            }
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("userId", m.userId);
            mm.put("nickname", m.nickname);
            mm.put("seat", s.name());
            members.add(mm);
        }
        msg.put("members", members);
        return msg;
    }
}
