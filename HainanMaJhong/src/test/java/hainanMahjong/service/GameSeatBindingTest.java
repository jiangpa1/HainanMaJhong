package hainanMahjong.service;

import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.RoomSupport;
import hainanMahjong.room.State;
import hainanMahjong.service.impl.MultiPlayerRoomServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：<b>绑定牌局座位时，必须同时把 /game 连接登记进 {@link RoomRegistry}</b>。
 *
 * <h3>为什么必须单独守这一条</h3>
 * <p>{@code MultiPlayerRoomServiceImpl.onGameMessage} 的第一件事是：</p>
 * <pre>
 *   Long userId = registry.gameUser(session);
 *   String code = registry.gameCode(session);
 *   if (userId == null || code == null) return;   // ← 静默丢弃
 * </pre>
 * <p>而这两个映射唯一的写入点，就是绑定座位的 {@code bindGameSeat}。
 * 一旦漏掉那一步，{@code /game} 上<b>所有</b>消息（出牌、吃碰杠胡、过、聊天、退出）
 * 都会在第一行被丢掉，玩家看到的是"点了完全没反应、后端也没有任何日志"——
 * 而建房、开局、收广播全都正常，因为那些路径不经过这个查表。</p>
 *
 * <p>这个 bug 真实发生过一次：{@code bindGameSession} 当时只有定义和
 * {@code unbindGameSession} 的调用，没有绑定调用。所以这里把它钉死。</p>
 */
class GameSeatBindingTest {

    /** 最小可用的假 session：只需要 id 与 sendMessage。 */
    private static WebSocketSession fakeSession(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        doAnswer(inv -> null).when(s).sendMessage(any(WebSocketMessage.class));
        return s;
    }

    @Test
    @DisplayName("bindGameSeat 之后，registry 必须能按 session 查到 userId 与房间码")
    void bindingSeatRegistersGameSession() throws Exception {
        RoomRegistry registry = new RoomRegistry();

        Room room = new Room("BIND01", 42L);
        room.state = State.PLAYING;

        // 四个座位都在，真人坐 WEST
        for (Seat s : Seat.values()) {
            Member m;
            if (s == Seat.WEST) {
                m = new Member(s, 42L, "真人", null);
            } else {
                m = new Member(s, RoomSupport.botUserId(s), "电脑", null);
                m.controller.setDelegate(new BotController(20260923L + s.ordinal()));
            }
            room.members.put(s, m);
        }
        registry.register(room);
        registry.bindUser(42L, room.code);

        WebSocketSession session = fakeSession("game-session-1");

        // 依赖为 mock：本测试只关心"绑定这一步有没有写进注册表"
        MultiPlayerRoomServiceImpl svc = new MultiPlayerRoomServiceImpl(
                mock(hainanMahjong.service.GamePushService.class),
                registry,
                mock(hainanMahjong.service.RoomService.class),
                mock(hainanMahjong.service.impl.GameEngineServiceImpl.class));

        // openGame 会解析 URI 拿 code/seat 再走 bindGameSeat。
        // 这里直接走同一条路：用带 query 的 session。
        WebSocketSession withQuery = mock(WebSocketSession.class);
        when(withQuery.getId()).thenReturn("game-session-1");
        when(withQuery.isOpen()).thenReturn(true);
        when(withQuery.getUri()).thenReturn(java.net.URI.create("/game?code=BIND01&seat=WEST&token=x"));
        /*
         * 必须提供握手阶段写入的会话属性。
         * openGame 第一步就是 authUserId(session) —— 它读的是
         * WsAuthHandshakeInterceptor 在握手时放进 attributes 的 userId。
         * 不设的话 authUserId 返回 0，openGame 会直接 closeQuietly 返回，
         * 整个测试就测不到 bindGameSeat（第一次写这个测试时就踩了这个坑）。
         */
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put(MultiPlayerRoomServiceImpl.ATTR_USER_ID, 42L);
        when(withQuery.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> null).when(withQuery).sendMessage(any(WebSocketMessage.class));

        svc.openGame(withQuery);

        // ===== 核心断言：注册表里必须查得到 =====
        Long userId = registry.gameUser(withQuery);
        String code = registry.gameCode(withQuery);

        assertNotNull(userId,
                "registry.gameUser(session) 为 null —— bindGameSeat 没有调用 bindGameSession。"
                        + "后果：/game 上所有消息都会被 onGameMessage 第一行的 null 检查静默丢弃，"
                        + "玩家表现为「点了出牌完全没反应」");
        assertNotNull(code,
                "registry.gameCode(session) 为 null —— 同上，/game 消息会被静默丢弃");
        assertEquals(42L, userId.longValue(), "查到的 userId 应为 42");
        assertEquals("BIND01", code, "查到的房间码应为 BIND01");

        // 顺带确认座位一致：绑的是 WEST
        assertEquals(Seat.WEST, room.members.get(Seat.WEST).seat, "真人应坐在 WEST");
        assertNotNull(session, "（占位断言，避免未使用告警）");
    }
}
