package hainanMahjong.service;

import hainanMahjong.engine.model.*;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.*;
import hainanMahjong.rules.*;
import hainanMahjong.service.impl.*;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.WebSocketSession;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Timeout(15)
class EventDrivenMatchTest {
    final RoomRegistry registry = new RoomRegistry();
    final RoomSnapshotRepository redis = mock(RoomSnapshotRepository.class);
    final RoundPersister persist = mock(RoundPersister.class);
    final GamePushService push = mock(GamePushService.class);
    final RoomSnapshotService snapshots = mock(RoomSnapshotService.class);
    final RoomServiceImpl rooms = new RoomServiceImpl(redis, registry, push, mock(UserMapper.class), persist);
    final GameEngineServiceImpl engine = new GameEngineServiceImpl(persist, push, snapshots, registry, rooms);
    final Room room = new Room("EVENT1", 1);
    final CompletableFuture<Map<String, Object>> request = new CompletableFuture<>();

    @BeforeEach void setup() {
        when(persist.createSession(anyString(), anyLong(), anyList())).thenReturn(42L);
        when(push.coinView(room)).thenAnswer(call -> {
            Map<String, Integer> coins = new HashMap<>();
            room.coins.forEach((seat, value) -> coins.put(seat.name(), value));
            return coins;
        });
        for (Seat seat : Seat.values()) {
            Member member = new Member(seat, seat.ordinal() + 1, seat.name(), null);
            member.human.attach(request::complete);
            room.members.put(seat, member);
        }
        registry.register(room);
    }

    @AfterEach void cleanup() throws Exception {
        rooms.stopAndClear(room);
        drain(); drain();
        engine.stopPushFlusher();
    }

    WebSocketSession socket() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }

    void drain() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        room.events.execute(() -> done.complete(null)); done.get(3, TimeUnit.SECONDS);
    }

    EngineStart exhaustedHand(boolean lastHand) {
        EngineStart seed = new EngineStart();
        seed.sessionId = 42; seed.cfg = HainanConfig.defaultConfig();
        seed.flow = DealerFlow.restore(seed.cfg, Seat.EAST, Seat.EAST, 1, 0, 1, 1,
                lastHand ? DealerFlow.MAX_HANDS : 0);
        GameSnapshot snap = new GameSnapshot();
        snap.currentSeat = "EAST"; snap.phase = "TURN"; snap.allowWin = true;
        snap.deck.addAll(Collections.nCopies(15, 32));
        for (Seat seat : Seat.values()) {
            GameSnapshot.PlayerState player = new GameSnapshot.PlayerState();
            player.hand.addAll(List.of(0, 1, 5, 8, 9, 12, 15, 18, 22, 25, 28, 30, 33));
            snap.players.put(seat.name(), player);
        }
        seed.resume = snap;
        return seed;
    }

    @Test void finalBindingEventStartsImmediatelyAndExactlyOnce() throws Exception {
        engine.startMatch(room); drain();
        assertTrue(room.awaitingBind); assertTrue(room.engineRunning);
        verify(persist, never()).createSession(anyString(), anyLong(), anyList());
        for (Member member : room.members.values()) member.gameWs = socket();
        for (Member member : room.members.values()) engine.pushSeatState(member.gameWs, room, member.seat);
        request.get(3, TimeUnit.SECONDS);
        assertFalse(room.awaitingBind); assertNull(room.engineTimer); assertNotNull(room.active);
        verify(persist, times(1)).createSession(anyString(), anyLong(), anyList());
    }

    @Test void closingWhileWaitingForBindingsCancelsDeadlineAndRejectsQueuedBind() throws Exception {
        engine.startMatch(room); drain();
        ScheduledFuture<?> deadline = room.engineTimer;
        rooms.stopAndClear(room);
        drain();
        assertTrue(deadline.isCancelled()); assertFalse(room.engineRunning); assertFalse(room.awaitingBind);
        assertNull(room.engineTimer); assertEquals(State.CLOSED, room.state);
        verify(persist, never()).createSession(anyString(), anyLong(), anyList());
    }

    @Test void completedHandCommitsOnceThenSchedulesPauseWithoutHoldingWorker() throws Exception {
        room.state = State.PLAYING;
        CompletableFuture<Void> committed = new CompletableFuture<>();
        doAnswer(call -> {
            Map<String, Object> message = call.getArgument(1);
            if ("coins".equals(message.get("type"))) committed.complete(null);
            return null;
        }).when(push).broadcastGame(eq(room), anyMap());
        EngineStart seed = exhaustedHand(false);
        engine.startEngine(room, seed); engine.startEngine(room, seed);
        committed.get(3, TimeUnit.SECONDS); drain();
        assertNull(room.active); assertNotNull(room.engineTimer); assertFalse(room.engineTimer.isDone());
        assertTrue(room.engineRunning); assertEquals(2, room.flow.handNo);
        verify(persist, times(1)).saveHand(eq(42L), eq(1), eq(true), eq(2),
                isNull(), isNull(), isNull(), isNull(), eq("{}"), eq(0), eq("EAST"), eq(0), anyList());
        ScheduledFuture<?> pause = room.engineTimer;
        rooms.stopAndClear(room); assertTrue(pause.isCancelled());
    }

    @Test void finalHandPauseTransitionsToBetweenAndRematchUsesSameSession() throws Exception {
        for (Member member : room.members.values()) member.gameWs = socket();
        room.state = State.PLAYING;
        CompletableFuture<Void> finished = new CompletableFuture<>();
        doAnswer(call -> {
            Map<String, Object> message = call.getArgument(1);
            if ("match_finish".equals(message.get("type"))) finished.complete(null);
            return null;
        }).when(push).sendJson(any(), anyMap());
        engine.startEngine(room, exhaustedHand(true));
        finished.get(5, TimeUnit.SECONDS); drain();
        assertEquals(State.BETWEEN, room.state); assertFalse(room.engineRunning);
        assertNull(room.active); assertEquals(42L, room.sessionId);
        room.lock.lock();
        try { engine.onRematch(room, room.hostUserId); } finally { room.lock.unlock(); }
        request.get(3, TimeUnit.SECONDS);
        assertEquals(State.PLAYING, room.state); assertTrue(room.engineRunning);
        assertEquals(42L, room.sessionId);
        verify(persist, never()).createSession(anyString(), anyLong(), anyList());
    }

    @Test void persistenceFailureDisbandsInsteadOfStartingAnotherHand() throws Exception {
        room.state = State.PLAYING;
        when(persist.saveHand(anyLong(), anyInt(), anyBoolean(), anyInt(), any(), any(), any(),
                any(), any(), anyInt(), anyString(), anyInt(), anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));
        CompletableFuture<Void> closed = new CompletableFuture<>();
        doAnswer(call -> { closed.complete(null); return true; }).when(redis).closeRoom(eq(room.code), anyString());
        engine.startEngine(room, exhaustedHand(false));
        closed.get(3, TimeUnit.SECONDS); drain();
        assertEquals(State.CLOSED, room.state); assertFalse(room.engineRunning);
        assertNull(room.engineTimer); assertNull(room.active);
    }
}
