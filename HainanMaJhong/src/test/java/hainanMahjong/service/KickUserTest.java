package hainanMahjong.service;

import hainanMahjong.engine.model.Seat;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.service.impl.RoomServiceImpl;
import hainanMahjong.service.impl.RoundPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单点登录：同一账号在别的设备登录时，要先把旧设备的连接踢掉。
 *
 * <p><b>为什么必须"先发消息再关连接"</b>：直接关掉，前端只会当成一次普通掉线
 * （牌桌会自动重连、重连又被握手拒掉），用户看到的是"一直在连接中"，
 * 完全不知道账号已经在别处登录。先推 {@code {"type":"kick"}}，
 * 前端才能弹提示并清登录态回登录页。</p>
 *
 * <p>纯 Mockito：这里验的是"发没发、发给谁、关没关"这个行为，不起服务、不连 Redis。</p>
 */
class KickUserTest {

    private RoomRegistry registry;
    private GamePushService push;
    private RoomServiceImpl svc;

    @BeforeEach
    void setUp() {
        RoomSnapshotRepository redis = mock(RoomSnapshotRepository.class);
        registry = mock(RoomRegistry.class);
        push = mock(GamePushService.class);
        UserMapper userMapper = mock(UserMapper.class);
        RoundPersister roundPersist = mock(RoundPersister.class);
        svc = new RoomServiceImpl(redis, registry, push, userMapper, roundPersist);
    }

    private WebSocketSession openSession() throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("踢人：先推 kick 消息，再关掉该账号的房间与牌局两条连接")
    void kicksBothSocketsAndPushesKickMessage() throws Exception {
        Room room = new Room("T10001", 7L);
        Member m = new Member(Seat.EAST, 7L, "阿明", openSession());
        m.gameWs = openSession();
        room.members.put(Seat.EAST, m);
        when(registry.allRooms()).thenReturn(List.of(room));

        int n = svc.kickUser(7L, "该账号在别处登录，请重新登录");

        assertEquals(2, n, "房间连接 + 牌局连接都要算上");
        // 内容：必须是 kick + reason，前端据 reason 提示
        verify(push, times(2)).sendJson(any(), org.mockito.ArgumentMatchers.argThat(msg ->
                "kick".equals(((Map<?, ?>) msg).get("type"))
                        && String.valueOf(((Map<?, ?>) msg).get("reason")).contains("别处登录")));
        verify(m.roomWs).close(eq(CloseStatus.NORMAL));
        verify(m.gameWs).close(eq(CloseStatus.NORMAL));
    }

    @Test
    @DisplayName("踢的是别人：同房间其它座位不受影响")
    void doesNotTouchOtherSeats() throws Exception {
        Room room = new Room("T10002", 7L);
        Member mine = new Member(Seat.EAST, 7L, "我", openSession());
        Member other = new Member(Seat.SOUTH, 8L, "别人", openSession());
        room.members.put(Seat.EAST, mine);
        room.members.put(Seat.SOUTH, other);
        when(registry.allRooms()).thenReturn(List.of(room));

        int n = svc.kickUser(7L, "该账号在别处登录，请重新登录");

        assertEquals(1, n);
        verify(other.roomWs, never()).close(any());
        verify(push, never()).sendJson(eq(other.roomWs), any());
    }

    @Test
    @DisplayName("没有任何房间时也不报错")
    void noRoomsIsFine() {
        when(registry.allRooms()).thenReturn(List.of());
        assertEquals(0, svc.kickUser(7L, "该账号在别处登录，请重新登录"));
    }
}
