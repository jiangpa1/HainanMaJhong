package hainanMahjong.service.impl;

import hainanMahjong.service.MultiPlayerRoomService;
import hainanMahjong.service.GamePushService;
import hainanMahjong.service.RoomService;
import hainanMahjong.room.State;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.EngineStart;
import hainanMahjong.room.RoomRegistry;


import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.rules.DealerFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多人联机房间：等待房 + 4 真人 16 局。
 *
 * <p>v2 关键规则：</p>
 * <ul>
 *   <li><b>对局中任何人（含房主）离开/刷新都不结束游戏</b>：座位保留、由机器人托管，
 *       该玩家回到大厅会看到“返回房间”按钮，可随时回座位；一个 16 局块结束仍有人未归才解散。</li>
 *   <li><b>多人局也把快照写进 Redis</b>（以房间码为键）：块内每把每次出牌前落一次全量快照；
 *       服务重启/崩溃后，只要有玩家凭房间码回来（或 /game 连回），就从 Redis 重建房间并继续把剩余局数打完。</li>
 *   <li>每 16 把 = 一条 MySQL game_sessions；房主可在块末发起下一轮。</li>
 * </ul>
 */
@Service
public class MultiPlayerRoomServiceImpl implements MultiPlayerRoomService {

    private static final Logger log = LoggerFactory.getLogger(MultiPlayerRoomServiceImpl.class);

    /**
     * WebSocket 握手阶段认证成功后写入会话属性的 key。
     *
     * <p>定义在这里而不是拦截器里，是为了保持依赖方向：拦截器（web 层）依赖 service，
     * service 不反向依赖 websocket 包。</p>
     */
    public static final String ATTR_USER_ID = "userId";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GamePushService push;
    private final RoomRegistry registry;
    private final RoomService rooms;
    private final GameEngineServiceImpl engine;

    public MultiPlayerRoomServiceImpl(GamePushService push, RoomRegistry registry,
                                      RoomService rooms, GameEngineServiceImpl engine) {
        this.push = push;
        this.registry = registry;
        this.rooms = rooms;
        this.engine = engine;
    }
    // ==================== /room 入口 ====================

    @Override
    public void open(WebSocketSession session) {
        long userId = authUserId(session);
        if (userId > 0) {
            registry.bindClientSession(session, userId);
        }
    }

    @Override
    public void onMessage(WebSocketSession session, String payload) {
        Long userId = registry.clientUser(session);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            if ("createRoom".equals(type)) {
                rooms.create(session, userId == null ? 0L : userId, msg.get("config"));
            } else if ("joinRoom".equals(type)) {
                String code = msg.get("code") == null ? "" : String.valueOf(msg.get("code")).trim();
                if (rooms.join(session, userId == null ? 0L : userId, code)) {
                    engine.startMatch(registry.find(code));
                }
            } else if ("fillBots".equals(type)) {
                engine.fillBotsFor(userId == null ? 0L : userId);
            } else if ("startNow".equals(type)) {
                engine.startNowFor(userId == null ? 0L : userId);
            } else if ("leaveRoom".equals(type)) {
                leave(userId == null ? 0L : userId);
            } else {
                push.sendJson(session, denied("未知指令: " + type));
            }
        } catch (Exception e) {
            log.warn("解析 /room 消息失败：{}", e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocketSession session) {
        Long userId = registry.clientUser(session);
        if (userId == null) {
            return;
        }
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return;
        }
        // 委派给房间模块：统一走"等待房直接移除座位 / 对局中保留座位"的规则
        rooms.onClientSocketClosed(session, userId);
    }

    // ==================== /game 入口 ====================

    @Override
    public void openGame(WebSocketSession session) {
        long userId = authUserId(session);
        String code = parseParam(session, "code");
        Seat seat = parseSeat(session);
        if (userId <= 0 || code == null || seat == null) {
            closeQuietly(session);
            return;
        }
        bindGameSeat(code, seat, userId, session);
    }

    @Override
    public void onGameMessage(WebSocketSession session, String payload) {
        // ===== 诊断日志（排查"点了出牌没反应"用）=====
        // 为什么要在最前面打：下面有 4 处静默 return，一旦命中就什么都不留，
        // 前端只看到"发出去了但没反应"，后端一行日志都没有，无从下手。
        // 这条日志能直接回答"服务端到底有没有收到这一帧"。
        // 【排查结束后可把这一段降级为 log.trace 或删掉】
        if (payload != null && payload.contains("\"discard\"")) {
            log.info("收到 discard 帧: sessionId={}, payload={}", session.getId(), payload);
        }

        Long userId = registry.gameUser(session);
        String code = registry.gameCode(session);
        if (userId == null || code == null) {
            if (payload != null && payload.contains("\"discard\"")) {
                log.warn("discard 帧被丢弃：连接未绑定 userId/code（userId={}, code={}）", userId, code);
            }
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            if (payload != null && payload.contains("\"discard\"")) {
                log.warn("discard 帧被丢弃：找不到房间 code={}", code);
            }
            return;
        }
        synchronized (room) {
            Member m = Member.byId(room, userId);
            if (m == null) {
                if (payload != null && payload.contains("\"discard\"")) {
                    log.warn("discard 帧被丢弃：房间里找不到 userId={} 的成员", userId);
                }
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = mapper.readValue(payload, Map.class);
                String type = (String) msg.get("type");
                int reqId = msg.get("reqId") == null ? -1 : ((Number) msg.get("reqId")).intValue();
                if ("discard".equals(type)) {
                    log.info("处理 discard: userId={}, reqId={}, tile={}", userId, reqId, msg.get("tile"));
                    m.human.discard(reqId, ((Number) msg.get("tile")).intValue());
                } else if ("act".equals(type)) {
                    m.human.act(reqId, ((Number) msg.get("index")).intValue());
                } else if ("pass".equals(type)) {
                    m.human.pass(reqId);
                } else if ("baoting".equals(type)) {
                    m.human.baoTing(reqId);
                } else if ("chat".equals(type)) {
                    String text = msg.get("text") == null ? "" : String.valueOf(msg.get("text")).trim();
                    if (!text.isEmpty() && text.length() <= 80) {
                        Map<String, Object> cm = new HashMap<>();
                        cm.put("type", "chat");
                        cm.put("seat", m.seat.name());
                        cm.put("text", text);
                        push.broadcastGame(room, cm); // 全桌都能看到
                    }
                } else if ("rematch".equals(type)) {
                    engine.onRematch(room, userId);
                } else if ("leaveRoom".equals(type) || "quit".equals(type)) {
                    rooms.handleMemberLeft(room, m);
                }
            } catch (Exception e) {
                log.warn("解析 /game 消息失败：{}", e.getMessage());
            }
        }
    }

    @Override
    public void onGameClose(WebSocketSession session) {
        Long userId = registry.gameUser(session);
        String code = registry.gameCode(session);
        registry.unbindGameSession(session);
        if (userId == null || code == null) {
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            Member m = Member.byId(room, userId);
            if (m == null || m.gameWs != session) {
                return;
            }
            m.gameWs = null;
            rooms.handleMemberLeft(room, m);
        }
    }

    /** 是否已没有任何真人在线（四人全离线/未连入；补位的机器人不计）。 */

    private void bindGameSeat(String code, Seat seat, long userId, WebSocketSession session) {
        Room room = registry.find(code);
        if (room == null) {
            room = rooms.resurrect(code); // 服务重启后凭 Redis 快照重建
        }
        if (room == null) {
            push.sendJson(session, denied("房间不存在或已解散"));
            return;
        }
        synchronized (room) {
            Member m = room.members.get(seat);
            if (m == null || m.userId != userId) {
                push.sendJson(session, denied("座位不匹配，请重进房间"));
                return;
            }
            m.gameWs = session;
            m.offline = false;
            m.human.attach(msg -> push.sendJson(session, msg));
            m.controller.setDelegate(m.human);
            m.human.resendPending();
            registry.bindUser(userId, code);

            /*
             * ⚠️ 这一行是必须的，之前漏掉了。
             *
             * onGameMessage 第一件事就是 registry.gameUser(session) / gameCode(session)，
             * 拿不到就直接 return —— 而这两个映射唯一的写入点就是本方法。
             * 漏掉的后果是：/game 上【所有】消息（出牌 / 吃碰杠胡 / 过 / 聊天 / 离开）
             * 全部在第一行被静默丢弃，表现为"点了出牌完全没反应、后端一行日志都没有"。
             *
             * 注意：/room 那条连接一直正常，因为它在 open() 里调了 bindClientSession；
             * 只有 /game 这条漏了，所以能建房、能开局、能收广播，就是点不动。
             */
            registry.bindGameSession(session, userId, code, seat);

            Map<String, Object> welcome = new HashMap<>();
            welcome.put("type", "welcome");
            welcome.put("seat", seat.name());
            welcome.put("roomId", code);
            List<Map<String, Object>> players = new ArrayList<>();
            for (Seat s : Seat.values()) {
                Member mm = room.members.get(s);
                if (mm == null) continue;
                Map<String, Object> p = new HashMap<>();
                p.put("userId", mm.userId);
                p.put("nickname", mm.nickname);
                p.put("seat", s.name());
                players.add(p);
            }
            welcome.put("players", players);
            push.sendJson(session, welcome);

            Map<String, Object> match = new HashMap<>();
            match.put("type", "match");
            match.put("total", -1); // 打满四风，不固定把数
            push.sendJson(session, match);

            if (room.state == State.PLAYING && room.active != null) {
                Map<String, Object> hs = new HashMap<>();
                hs.put("type", "hand_start");
                hs.put("hand", room.currentHand);
                hs.put("total", -1);
                hs.put("dealer", room.currentDealer == null ? Seat.EAST.name() : room.currentDealer.name());
                if (room.flow != null) {
                    hs.put("wind", room.flow.windIdx());
                    hs.put("windName", DealerFlow.windName(room.flow.windIdx()));
                    hs.put("bottom", room.flow.bottom);
                }
                hs.put("coins", push.coinView(room));
                push.sendJson(session, hs);
                engine.pushSeatState(session, room, m.seat);
            }
            // 重启后第一次有人连回：若 Redis 说本块正在打，则续跑本块
            if (room.state == State.PLAYING && room.blockThread == null && room.resumeSeed != null) {
                EngineStart seed = room.resumeSeed;
                room.resumeSeed = null;
                engine.startEngine(room, seed);
            }
            log.info("座位 {} 玩家 {} 连入对局 {}", seat, userId, code);
        }
    }

    // ==================== 创建 / 加入 / 离开（等待房） ====================


    /** 创建房间配置：前端传入 JSON 对象 → HainanConfig；缺省用默认。 */


    @Override
    public void leave(long userId) {
        if (userId <= 0) {
            return;
        }
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            registry.unbindUser(userId, code);
            return;
        }
        synchronized (room) {
            Member m = Member.byId(room, userId);
            if (m != null) {
                rooms.handleMemberLeft(room, m);
            }
        }
    }
    // ==================== 大厅“返回房间” ====================

    /** 登录用户是否有一个可返回的房间（中途离开但房间还在）。 */
    @Override
    public Map<String, Object> pendingReturn(long userId) {
        Map<String, Object> out = new HashMap<>();
        out.put("exists", false);
        if (userId <= 0) {
            return out;
        }
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return out;
        }
        Room room = registry.find(code);
        if (room == null) {
            return out;
        }
        synchronized (room) {
            Member m = Member.byId(room, userId);
            if (m == null) {
                return out;
            }
            out.put("exists", true);
            out.put("code", code);
            out.put("state", room.state.name());
            out.put("seat", m.seat.name());
            out.put("hostUserId", room.hostUserId);
        }
        return out;
    }

    private static Seat parseSeat(WebSocketSession session) {
        String v = parseParam(session, "seat");
        if (v == null) {
            return null;
        }
        try {
            return Seat.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取当前连接已认证的 userId。
     *
     * <p>身份由 {@code WsAuthHandshakeInterceptor} 在握手时校验 token 后写入
     * {@link #ATTR_USER_ID}，这里只负责读。URL 上的 {@code ?userId=} 不再被读取——
     * 那个值是客户端随手写的，信它就等于没有鉴权。</p>
     *
     * <p>返回 0 表示"未认证"，调用方按游客处理（/room 允许游客占位，/game 直接断开）。</p>
     */
    private static long authUserId(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_USER_ID);
        return (v instanceof Number n) ? n.longValue() : 0L;
    }

    private static String parseParam(WebSocketSession session, String name) {
        try {
            String q = session.getUri() == null ? null : session.getUri().getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    if (kv.startsWith(name + "=")) {
                        return kv.substring(name.length() + 1);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    private Map<String, Object> denied(String reason) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "join_denied");
        msg.put("reason", reason);
        return msg;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            // 忽略
        }
    }

}
