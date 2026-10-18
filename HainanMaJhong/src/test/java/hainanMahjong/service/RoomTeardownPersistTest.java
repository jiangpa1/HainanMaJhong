package hainanMahjong.service;

import hainanMahjong.mapper.UserMapper;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.State;
import hainanMahjong.service.impl.RoomServiceImpl;
import hainanMahjong.service.impl.RoundPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 房间销毁时必须把这一场的 {@code end_time} 落库。
 *
 * <p><b>为什么值得单独测</b>：{@code stopAndClear} 原来只清内存
 * （state/stop/注销注册表/删 Redis），完全没碰数据库 —— 于是
 * {@code game_sessions.end_time} 一直是 NULL、{@code status} 一直停在"进行中"，
 * 那一场在战绩页永远显示成没打完，也永远排不进已完成列表。
 * 这类"内存清了、库没清"的问题没有报错、没有异常，只能靠断言盯住。</p>
 *
 * <p>用 Mockito 而不是真库：这里要验的是"有没有调、参数对不对"这个行为，
 * 不是 SQL 本身；不依赖数据库的测试在 CI 里更稳。</p>
 */
class RoomTeardownPersistTest {

    @Test void outboxCommitPrecedesRedisAndImmediateAck() {
        var outbox = mock(hainanMahjong.service.impl.RoomCloseOutbox.class);
        svc.setCloseOutbox(outbox);
        Room room = new Room("T00001", 7L); room.sessionId = 777L;
        when(redis.closeRoom(room.code, room.generation)).thenReturn(true);
        svc.stopAndClear(room);
        var order = inOrder(roundPersist, redis, outbox);
        order.verify(roundPersist).closeWithOutbox(777, room.code, room.generation);
        order.verify(redis).closeRoom(room.code, room.generation);
        order.verify(outbox).complete(777, room.generation);
        verify(roundPersist, never()).disbandSession(anyLong());
    }

    @Test void failedRedisLeavesDurableJobUnacknowledgedButCleansMemory() {
        var outbox = mock(hainanMahjong.service.impl.RoomCloseOutbox.class);
        svc.setCloseOutbox(outbox);
        Room room = new Room("T00001", 7L); room.sessionId = 777L;
        when(redis.closeRoom(room.code, room.generation)).thenThrow(new IllegalStateException());
        svc.stopAndClear(room);
        verify(outbox, never()).complete(anyLong(), anyString());
        verify(registry).unregister(room);
        assertEquals(State.CLOSED, room.state);
    }

    private RoomSnapshotRepository redis;
    private RoomRegistry registry;
    private GamePushService push;
    private UserMapper userMapper;
    private RoundPersister roundPersist;
    private RoomServiceImpl svc;

    @BeforeEach
    void setUp() {
        redis = mock(RoomSnapshotRepository.class);
        registry = mock(RoomRegistry.class);
        push = mock(GamePushService.class);
        userMapper = mock(UserMapper.class);
        roundPersist = mock(RoundPersister.class);
        svc = new RoomServiceImpl(redis, registry, push, userMapper, roundPersist);
    }

    @Test
    @DisplayName("销毁房间时把本场 session 标记结束（写 end_time）")
    void stopAndClearPersistsEndTime() {
        Room room = new Room("T00001", 7L);
        room.sessionId = 777L;
        room.state = State.PLAYING;

        svc.stopAndClear(room);

        verify(roundPersist).disbandSession(777L);
        assertEquals(State.CLOSED, room.state, "销毁后必须明确标记 CLOSED");
        assertTrue(room.stop, "销毁后应置 stop，让引擎循环退出");
    }

    @Test
    @DisplayName("没有 session（还没开局就散了）时不去写库")
    void stopAndClearSkipsPersistWhenNoSession() {
        Room room = new Room("T00002", 7L);
        room.sessionId = -1L; // 未创建
        room.state = State.WAITING;

        svc.stopAndClear(room);

        verify(roundPersist, never()).disbandSession(anyLong());
    }

    @Test
    @DisplayName("写库失败不能把销毁流程带崩")
    void persistFailureDoesNotBreakTeardown() {
        Room room = new Room("T00003", 7L);
        room.sessionId = 888L;
        room.state = State.PLAYING;
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(roundPersist).disbandSession(888L);

        svc.stopAndClear(room);

        assertEquals(State.CLOSED, room.state, "写库失败也要把房间正常销毁掉");
        assertTrue(room.stop);
    }
}
