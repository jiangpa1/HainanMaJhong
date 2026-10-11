package hainanMahjong.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.*;
import hainanMahjong.service.impl.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomLifecycleTest {
    private final RoomSnapshotRepository redis = mock(RoomSnapshotRepository.class);
    private final RoomRegistry registry = new RoomRegistry();
    private final RoundPersister persist = mock(RoundPersister.class);
    private final RoomServiceImpl rooms = new RoomServiceImpl(redis, registry,
            mock(GamePushService.class), mock(UserMapper.class), persist);
    private final ObjectMapper mapper = new ObjectMapper();

    private Room populated(State state) {
        Room room = new Room("123456", 1);
        room.state = state;
        room.sessionId = 42;
        for (Seat s : Seat.values()) room.members.put(s, new Member(s, s.ordinal() + 1, "player", null));
        return room;
    }

    private String snapshot(State state) throws Exception {
        RedisRoom rs = new RedisRoom();
        rs.generation = "saved-generation";
        rs.version = 5;
        rs.state = state.name();
        rs.hostUserId = 1;
        rs.sessionId = 42;
        rs.currentHand = 3;
        rs.flowHandNo = 4;
        rs.firstDealer = "EAST";
        for (Seat s : Seat.values()) {
            MemberInfo m = new MemberInfo(); m.seat = s.name(); m.userId = s.ordinal() + 1;
            rs.members.add(m);
        }
        GameSessions db = new GameSessions(); db.setId(42L); db.setRoomId("123456");
        db.setCreateId(1L); db.setPlayerIds("[1,2,3,4]");
        when(persist.sessionForRestore(42)).thenReturn(db);
        when(redis.claimRestore(eq("123456"), anyString(), anyString(), anyLong(), eq(42L))).thenReturn(true);
        String json = mapper.writeValueAsString(rs);
        when(redis.getRoomState("123456")).thenReturn(json);
        return json;
    }

    @Test void closeIsIdempotentAndCannotSaveAgain() {
        Room room = populated(State.PLAYING); registry.register(room);
        rooms.stopAndClear(room); rooms.stopAndClear(room);
        new RoomSnapshotServiceImpl(redis).save(room, null);
        assertEquals(State.CLOSED, room.state); assertTrue(room.stop);
        assertTrue(room.members.isEmpty()); assertNull(registry.find(room.code));
        verify(persist, times(1)).disbandSession(42);
        verify(redis, times(1)).closeRoom(room.code, room.generation);
        verify(redis, never()).saveRoomState(anyString(), anyString(), anyLong(), anyLong(), anyString());
    }

    @Test void snapshotIoDoesNotHoldRoomLockAndDelayedSaveCannotChangeClosedState() throws Exception {
        Room room = populated(State.PLAYING); registry.register(room);
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            assertFalse(Thread.holdsLock(room)); captured.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS)); return false;
        }).when(redis).saveRoomState(anyString(), anyString(), anyLong(), anyLong(), anyString());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> saved = worker.submit(() -> new RoomSnapshotServiceImpl(redis).save(room, null));
            assertTrue(captured.await(3, TimeUnit.SECONDS));
            rooms.stopAndClear(room); release.countDown(); saved.get(3, TimeUnit.SECONDS);
            assertEquals(State.CLOSED, room.state); assertNull(registry.find(room.code));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void legitimateBetweenSnapshotRestoresSameGenerationAndVersion() throws Exception {
        snapshot(State.BETWEEN);
        Room restored = rooms.resurrect("123456");
        assertNotNull(restored); assertEquals(State.BETWEEN, restored.state);
        assertEquals("saved-generation", restored.generation); assertEquals(5, restored.snapshotVersion);
        assertEquals(4, restored.members.size()); assertSame(restored, rooms.resurrect("123456"));
    }

    @Test void closedEmptyAndDuplicateMemberSnapshotsAreRejected() throws Exception {
        String json = snapshot(State.CLOSED); assertNull(rooms.resurrect("123456"));
        RedisRoom rs = mapper.readValue(json, RedisRoom.class); rs.state = "BETWEEN"; rs.members.clear();
        when(redis.getRoomState("123456")).thenReturn(mapper.writeValueAsString(rs));
        assertNull(rooms.resurrect("123456"));
        json = snapshot(State.BETWEEN); rs = mapper.readValue(json, RedisRoom.class);
        rs.members.get(1).seat = "EAST";
        when(redis.getRoomState("123456")).thenReturn(mapper.writeValueAsString(rs));
        assertNull(rooms.resurrect("123456"));
    }

    @Test void endedSessionWrongOwnerAndWrongPlayersCannotRestore() throws Exception {
        snapshot(State.BETWEEN); GameSessions db = persist.sessionForRestore(42);
        db.setStatus(2); assertNull(rooms.resurrect("123456"));
        db.setStatus(0); db.setRoomId("654321"); assertNull(rooms.resurrect("123456"));
        db.setRoomId("123456"); db.setPlayerIds("[1,2,3,9]"); assertNull(rooms.resurrect("123456"));
    }

    @Test void changedSnapshotOrClosedGuardCannotRestore() throws Exception {
        snapshot(State.BETWEEN);
        when(redis.claimRestore(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(false);
        assertNull(rooms.resurrect("123456")); assertEquals(0, registry.roomCount());
    }

    @Test void stalePlayingSnapshotCannotReplayCommittedRound() throws Exception {
        snapshot(State.PLAYING); when(persist.lastCommittedRound(42)).thenReturn(4);
        assertNull(rooms.resurrect("123456"));
    }

    @Test void localCloseBlocksRestoreWhenRedisCloseFailed() throws Exception {
        String json = snapshot(State.BETWEEN);
        Room room = new Room("123456", 1, "saved-generation"); registry.register(room);
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).closeRoom(anyString(), anyString());
        rooms.stopAndClear(room);
        when(redis.getRoomState("123456")).thenReturn(json);
        assertNull(rooms.resurrect("123456"));
        verify(redis, times(3)).closeRoom(room.code, room.generation);
    }

    @Test void oldRoomRemovalCannotUnregisterReusedCode() {
        Room old = populated(State.PLAYING); registry.register(old); registry.unregister(old);
        Room next = new Room(old.code, 9); registry.register(next);
        registry.unregister(old); assertSame(next, registry.find(old.code));
        assertEquals(old.code, registry.codeOfUser(9));
    }

    @Test void conflictingMemberBindingCannotBeOverwrittenByRestore() throws Exception {
        snapshot(State.BETWEEN); registry.bindUser(2, "654321");
        assertNull(rooms.resurrect("123456")); assertEquals("654321", registry.codeOfUser(2));
    }

    @Test void closedRoomCannotStartEngineOrMatch() {
        Room room = populated(State.CLOSED); room.stop = true;
        GameEngineServiceImpl engine = new GameEngineServiceImpl(persist, mock(GamePushService.class),
                mock(RoomSnapshotService.class), registry, rooms);
        engine.startMatch(room); engine.startEngine(room, new EngineStart());
        assertNull(room.blockThread); assertTrue(room.stop); assertEquals(State.CLOSED, room.state);
    }

    @Test void legacyBetweenSnapshotIsMigratedAfterValidation() throws Exception {
        RedisRoom rs = mapper.readValue(snapshot(State.BETWEEN), RedisRoom.class);
        rs.generation = null; rs.version = 0;
        when(redis.getRoomState("123456")).thenReturn(mapper.writeValueAsString(rs));
        Room restored = rooms.resurrect("123456");
        assertNotNull(restored); assertTrue(restored.generation.startsWith("legacy-"));
    }

    @Test void simultaneousRestoreRegistersOneRoom() throws Exception {
        snapshot(State.BETWEEN);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Room> restore = () -> { assertTrue(start.await(2, TimeUnit.SECONDS)); return rooms.resurrect("123456"); };
            Future<Room> a = workers.submit(restore), b = workers.submit(restore);
            start.countDown(); Room first = a.get(3, TimeUnit.SECONDS);
            assertNotNull(first); assertSame(first, b.get(3, TimeUnit.SECONDS)); assertEquals(1, registry.roomCount());
        } finally { workers.shutdownNow(); }
    }

    @Test void oldGameConnectionDoesNotBelongToNewGeneration() {
        Room old = populated(State.PLAYING); registry.register(old);
        org.springframework.web.socket.WebSocketSession ws = mock(org.springframework.web.socket.WebSocketSession.class);
        when(ws.getId()).thenReturn("old-connection");
        registry.bindGameSession(ws, 1, old.code, Seat.EAST);
        assertTrue(registry.isGameSessionCurrent(ws, old));
        registry.unregister(old);
        Room next = new Room(old.code, 1); registry.register(next);
        assertFalse(registry.isGameSessionCurrent(ws, next));
    }

    @Test void committedBlockBoundaryRestoresBetweenInsteadOfStartingAnotherHand() throws Exception {
        RedisRoom rs = mapper.readValue(snapshot(State.PLAYING), RedisRoom.class);
        rs.flowFinished = true;
        when(redis.getRoomState("123456")).thenReturn(mapper.writeValueAsString(rs));
        Room restored = rooms.resurrect("123456");
        assertNotNull(restored); assertEquals(State.BETWEEN, restored.state); assertNull(restored.resumeSeed);
    }

    @Test void snapshotCapturesNextDealerAfterSettlement() throws Exception {
        Room room = populated(State.PLAYING);
        room.cfg = hainanMahjong.rules.HainanConfig.defaultConfig();
        room.flow = new hainanMahjong.rules.DealerFlow(room.cfg, Seat.EAST);
        room.flow.dealer = Seat.SOUTH; room.currentDealer = Seat.EAST;
        new RoomSnapshotServiceImpl(redis).save(room, null);
        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).saveRoomState(eq(room.code), eq(room.generation), eq(1L), eq(42L), json.capture());
        assertEquals("SOUTH", mapper.readValue(json.getValue(), RedisRoom.class).dealer);
    }

    @Test void oldLobbySocketCannotCloseNewRoom() {
        org.springframework.web.socket.WebSocketSession oldWs = mock(org.springframework.web.socket.WebSocketSession.class);
        when(oldWs.getId()).thenReturn("old-lobby"); registry.bindClientSession(oldWs, 1);
        Room next = populated(State.WAITING); registry.register(next);
        next.members.get(Seat.EAST).roomWs = mock(org.springframework.web.socket.WebSocketSession.class);
        RoomService roomService = mock(RoomService.class);
        MultiPlayerRoomServiceImpl facade = new MultiPlayerRoomServiceImpl(mock(GamePushService.class),
                registry, roomService, mock(GameEngineServiceImpl.class));
        facade.onMessage(oldWs, "{\"type\":\"leaveRoom\"}");
        verifyNoInteractions(roomService); assertSame(next, registry.find(next.code));
    }
}
