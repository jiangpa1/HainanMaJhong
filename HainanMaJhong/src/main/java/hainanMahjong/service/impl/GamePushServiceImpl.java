package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.service.GamePushService;
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
 *   <li><b>无锁</b>：不自带锁，调用方通常已持有 {@code synchronized (room)}</li>
 *   <li><b>无 I/O</b>：不碰 MySQL / Redis，只有 WebSocket 发送</li>
 * </ul>
 *
 * <p>{@link #sendJson} 是全项目唯一的 WebSocket 发送出口：它统一处理
 * "session 已关闭"、发送异常，并对 session 加锁 —— 避免两个线程同时往同一个
 * WebSocket 写帧（原生 WebSocket 的 {@code sendMessage} 不是线程安全的）。</p>
 */
@Service
public class GamePushServiceImpl implements GamePushService {

    private static final Logger log = LoggerFactory.getLogger(GamePushServiceImpl.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** 向房间内所有真人的牌局连接广播（机器人跳过；连接未打开的跳过）。 */
    @Override
    public void broadcastGame(Room room, Map<String, Object> msg) {
        for (Member m : new ArrayList<>(room.members.values())) {
            if (m == null || m.userId < 0 || m.gameWs == null || !m.gameWs.isOpen()) {
                continue;
            }
            sendJson(m.gameWs, msg);
        }
    }

    /**
     * 按座位发不同内容（暗杠用）。
     *
     * <p>判据是成员的 {@code seat} 而不是 userId：调用方拿到的就是座位枚举，
     * 而"谁该看到真实牌面"这件事本来就是按座位定的。</p>
     */
    @Override
    public void broadcastGameMasked(Room room, Seat owner, Map<String, Object> full, Map<String, Object> masked) {
        for (Member m : new ArrayList<>(room.members.values())) {
            if (m == null || m.userId < 0 || m.gameWs == null || !m.gameWs.isOpen()) {
                continue;
            }
            sendJson(m.gameWs, m.seat == owner ? full : masked);
        }
    }

    /**
     * 唯一的发送出口。
     *
     * <p>对 session 加锁是必须的：原生 WebSocket 的 {@code sendMessage} 不是线程安全的，
     * 而房间线程与 WebSocket 线程可能同时发消息。</p>
     */
    @Override
    public void sendJson(WebSocketSession session, Map<String, Object> msg) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("发送消息失败：{}", e.getMessage());
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

    /** 房间信息：发给指定连接，或发给房内全部等待房连接。 */
    @Override
    public void sendRoomInfo(Room room, WebSocketSession only) {
        Map<String, Object> msg = roomInfoMsg(room);
        if (only != null) {
            sendJson(only, msg);
        } else {
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m.roomWs != null && m.roomWs.isOpen()) {
                    sendJson(m.roomWs, msg);
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
