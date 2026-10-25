package hainanMahjong.engine;

import hainanMahjong.engine.model.*;
import hainanMahjong.engine.port.*;
import hainanMahjong.engine.human.HumanPlayerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class EventDrivenEngineTest {
    private static class Controller implements PlayerController {
        final CompletableFuture<Responder> discard = new CompletableFuture<>();
        volatile boolean virtual;
        public void onDiscardTurn(Seat s, List<Integer> hand, int drawn, List<Integer> banned, Responder r) {
            virtual = Thread.currentThread().isVirtual();
            discard.complete(r);
        }
        public void onDrawChance(Seat s, int tile, List<Action> options, Responder r) { r.pass(); }
        public void onActionChance(Seat s, int tile, List<Action> options, Responder r) { r.pass(); }
    }

    private GameConfig config(long timeout, int maxTurns, boolean hainan) {
        GameConfig cfg = new GameConfig(false, Seat.EAST, timeout, timeout, 2026, true, maxTurns, hainan, 0);
        cfg.fanGate = false;
        return cfg;
    }

    private RoomManager room(PlayerController controller, GameConfig cfg, GameListener listener) {
        Map<Seat, PlayerController> controllers = new EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) controllers.put(seat, controller);
        return new RoomManager(cfg, controllers, listener);
    }

    @Test void hundredsOfWaitingRoomsDoNotRetainPerRoomThreadsAndCancelPromptly() throws Exception {
        List<RoomManager> rooms = new ArrayList<>();
        List<CompletableFuture<RoundResult>> done = new ArrayList<>();
        List<Controller> controllers = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                Controller controller = new Controller();
                RoomManager room = room(controller, config(30000, 0, false), NoopGameListener.INSTANCE);
                controllers.add(controller); rooms.add(room); done.add(room.startAsync());
            }
            for (Controller c : controllers) c.discard.get(5, TimeUnit.SECONDS);
            assertTrue(done.stream().noneMatch(CompletableFuture::isDone));
            Set<Thread> threads = Thread.getAllStackTraces().keySet();
            assertEquals(0, threads.stream().filter(t -> t.getName().startsWith("mahjong-event-")).count());
            assertTrue(controllers.stream().allMatch(c -> c.virtual));
            assertEquals(1, threads.stream().filter(t -> t.getName().equals("mahjong-deadlines")).count());
            assertEquals(0, threads.stream().filter(t -> t.getName().equals("mahjong-timer")
                    || t.getName().startsWith("room-bind-") || t.getName().startsWith("room-engine-")).count());
        } finally {
            rooms.forEach(RoomManager::cancel);
            for (CompletableFuture<RoundResult> future : done) future.get(5, TimeUnit.SECONDS);
        }
        for (RoomManager r : rooms) {
            assertEquals(RoomManager.Phase.CANCELLED, r.phase());
            assertEquals(0, r.pendingTimerCount());
        }
    }

    @Test void duplicateAndLateRepliesNeverProduceAnExtraDiscard() throws Exception {
        Controller c = new Controller();
        AtomicInteger discards = new AtomicInteger(), ends = new AtomicInteger();
        GameListener listener = new TestListener() {
            public void onDiscard(Seat seat, int tile) {
                assertTrue(Thread.currentThread().isVirtual());
                discards.incrementAndGet();
            }
            public void onEnd(RoundResult result) { ends.incrementAndGet(); }
        };
        RoomManager room = room(c, config(30000, 1, false), listener);
        CompletableFuture<RoundResult> done = room.startAsync();
        Responder responder = c.discard.get(3, TimeUnit.SECONDS);
        int tile = room.getPlayer(Seat.EAST).hand.get(0);
        responder.discard(tile); responder.discard(tile); responder.pass();
        assertTrue(done.get(3, TimeUnit.SECONDS).isDraw);
        assertEquals(1, discards.get()); assertEquals(1, ends.get());
        assertEquals(RoomManager.Phase.ENDED, room.phase());
        assertEquals(0, room.pendingTimerCount());
    }

    @Test void cancelDoesNotApplyForcedDiscardOrPublishEnd() throws Exception {
        Controller c = new Controller();
        AtomicInteger discards = new AtomicInteger(), ends = new AtomicInteger();
        RoomManager room = room(c, config(30000, 0, false), new TestListener() {
            public void onDiscard(Seat s, int t) { discards.incrementAndGet(); }
            public void onEnd(RoundResult r) { ends.incrementAndGet(); }
        });
        CompletableFuture<RoundResult> done = room.startAsync();
        Responder reply = c.discard.get(3, TimeUnit.SECONDS);
        room.cancel();
        assertNull(done.get(3, TimeUnit.SECONDS));
        reply.discard(0);
        assertEquals(0, discards.get()); assertEquals(0, ends.get());
        assertEquals(14, room.getPlayer(Seat.EAST).hand.size());
    }

    @Test void timeoutRetiresHumanRequestAndContinuesOnMailbox() throws Exception {
        List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        HumanPlayerController human = new HumanPlayerController(sent::add, 20, 20);
        AtomicInteger timeouts = new AtomicInteger();
        RoomManager room = room(human, config(20, 1, false), new TestListener() {
            public void onTimeout(Seat s, String what) {
                assertTrue(Thread.currentThread().isVirtual());
                timeouts.incrementAndGet();
            }
        });
        assertTrue(room.startAsync().get(3, TimeUnit.SECONDS).isDraw);
        assertTrue(timeouts.get() >= 1);
        int count = sent.size();
        human.resendPending();
        assertEquals(count, sent.size(), "Completed requests must not be replayed after reconnect");
        for (Map<String, Object> message : sent) human.discard((Integer) message.get("reqId"), 0);
        assertEquals(1, room.getPlayerDiscards(Seat.EAST).size());
    }

    @Test void replyRacingForceCompletesExactlyOnceOnOwnerMailbox() throws Exception {
        RoomEventLoop loop = new RoomEventLoop();
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                PromptSupport prompt = new PromptSupport(NoopGameListener.INSTANCE, loop);
                CompletableFuture<Consumer<Integer>> reply = new CompletableFuture<>();
                CompletableFuture<Integer> done = new CompletableFuture<>();
                AtomicInteger completions = new AtomicInteger();
                prompt.promptAsync(Seat.EAST, "test", 30000, reply::complete, -1, value -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    completions.incrementAndGet(); done.complete(value);
                });
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> a = racers.submit(() -> { await(barrier); reply.join().accept(7); });
                Future<?> b = racers.submit(() -> { await(barrier); prompt.forceNow(); });
                a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS);
                assertTrue(Set.of(-1, 7).contains(done.get(2, TimeUnit.SECONDS)));
                CompletableFuture<Void> drained = new CompletableFuture<>();
                loop.execute(() -> drained.complete(null)); drained.get(2, TimeUnit.SECONDS);
                assertEquals(1, completions.get()); assertEquals(0, prompt.pendingTimerCount());
                prompt.shutdown();
            }
        } finally { racers.shutdownNow(); }
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void listenerFailureCompletesExceptionallyAndCancelsTimers() throws Exception {
        RoomManager room = room(new Controller(), config(30000, 0, false), new TestListener() {
            public void onDeal(Seat seat) { throw new IllegalStateException("listener failed"); }
        });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> room.startAsync().get(3, TimeUnit.SECONDS));
        assertEquals("listener failed", failure.getCause().getMessage());
        assertEquals(RoomManager.Phase.FAILED, room.phase());
        assertEquals(0, room.pendingTimerCount());
    }

    private GameSnapshot snapshot(boolean respond) {
        GameSnapshot snap = new GameSnapshot();
        snap.currentSeat = Seat.EAST.name(); snap.phase = respond ? "RESPOND" : "TURN";
        snap.skipDraw = true; snap.allowWin = true;
        snap.deck.addAll(Collections.nCopies(30, 32));
        for (Seat seat : Seat.values()) {
            GameSnapshot.PlayerState p = new GameSnapshot.PlayerState();
            p.hand.addAll(List.of(0, 1, 5, 8, 9, 12, 15, 18, 22, 25, 28, 30, 33));
            snap.players.put(seat.name(), p); snap.discards.put(seat.name(), new ArrayList<>());
        }
        snap.players.get("SOUTH").hand = new ArrayList<>(List.of(2, 3, 5, 6, 7, 9, 10, 11, 18, 19, 20, 27, 27));
        if (respond) { snap.discardPile.add(4); snap.discards.get("EAST").add(4); }
        return snap;
    }

    @Test void restoredClaimUsesActualChoicePriorityBeforeChangingHands() throws Exception {
        GameSnapshot snap = snapshot(true);
        snap.players.get("WEST").hand = new ArrayList<>(List.of(4, 4, 0, 0, 1, 1, 8, 8, 12, 12, 21, 21, 30));
        Map<Seat, PlayerController> controllers = new EnumMap<>(Seat.class);
        List<Seat> asked = new CopyOnWriteArrayList<>();
        Controller west = new Controller() {
            public void onActionChance(Seat s, int tile, List<Action> options, Responder reply) {
                asked.add(s); reply.act(options.stream().filter(a -> a.type() == Action.Type.PENG).findFirst().orElseThrow());
            }
        };
        for (Seat seat : Seat.values()) controllers.put(seat, new Controller());
        controllers.put(Seat.WEST, west);
        controllers.put(Seat.SOUTH, new Controller() {
            public void onActionChance(Seat s, int tile, List<Action> options, Responder reply) {
                asked.add(s);
                assertTrue(options.stream().anyMatch(a -> a.type() == Action.Type.HU));
                reply.act(options.stream().filter(a -> a.type() == Action.Type.CHI).findFirst().orElseThrow());
            }
        });
        RoomManager room = new RoomManager(config(30000, 0, false), controllers, NoopGameListener.INSTANCE);
        CompletableFuture<RoundResult> done = room.resumeAsync(snap);
        try {
            west.discard.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(Seat.SOUTH, Seat.WEST), asked);
            assertEquals(13, room.getPlayer(Seat.SOUTH).hand.size());
            assertTrue(room.getPlayer(Seat.SOUTH).melds.isEmpty());
            assertEquals(Meld.Type.PENG, room.getPlayer(Seat.WEST).melds.get(0).type());
        } finally { room.cancel(); done.get(3, TimeUnit.SECONDS); }
    }

    @Test void robKongSuspendsBeforeMeldMutationAndWinsFromRestoredTurn() throws Exception {
        GameSnapshot snap = snapshot(false);
        snap.players.get("EAST").hand = new ArrayList<>(List.of(4, 0, 1, 2, 9, 10, 11, 18, 19, 20, 27));
        GameSnapshot.MeldState peng = new GameSnapshot.MeldState();
        peng.type = "PENG"; peng.tiles.addAll(List.of(4, 4, 4)); peng.from = "WEST";
        snap.players.get("EAST").melds.add(peng);
        CompletableFuture<Responder> rob = new CompletableFuture<>();
        AtomicReference<Action> hu = new AtomicReference<>();
        Map<Seat, PlayerController> controllers = new EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) controllers.put(seat, new Controller());
        controllers.put(Seat.EAST, new Controller() {
            public void onDrawChance(Seat s, int tile, List<Action> options, Responder reply) {
                reply.act(options.stream().filter(a -> a.type() == Action.Type.BU_GANG).findFirst().orElseThrow());
            }
        });
        controllers.put(Seat.SOUTH, new Controller() {
            public void onActionChance(Seat s, int tile, List<Action> options, Responder reply) {
                hu.set(options.get(0)); rob.complete(reply);
            }
        });
        RoomManager room = new RoomManager(config(30000, 0, true), controllers, NoopGameListener.INSTANCE);
        CompletableFuture<RoundResult> done = room.resumeAsync(snap);
        try {
            Responder reply = rob.get(3, TimeUnit.SECONDS);
            assertEquals(RoomManager.Phase.ROB_KONG, room.phase());
            assertEquals(Meld.Type.PENG, room.getPlayer(Seat.EAST).melds.get(0).type());
            reply.act(hu.get());
            RoundResult result = done.get(3, TimeUnit.SECONDS);
            assertTrue(result.qiangGang); assertEquals(Seat.SOUTH, result.winner); assertEquals(Seat.EAST, result.from);
            assertEquals(Meld.Type.PENG, room.getPlayer(Seat.EAST).melds.get(0).type());
        } finally { room.cancel(); }
    }

    private static class TestListener implements GameListener {
        public void onShuffle(int n) { }
        public void onDeal(Seat s) { }
        public void onDraw(Seat s, int t) { }
        public void onFlower(Seat s, int t) { }
        public void onDiscard(Seat s, int t) { }
        public void onMeld(Seat s, Meld m) { }
        public void onHu(Seat s, hainanMahjong.rules.HuResult h, boolean sd, boolean th, int bt, int t, Seat f) { }
        public void onTimeout(Seat s, String action) { }
        public void onRoundDraw() { }
        public void onResume() { }
        public void onEnd(RoundResult r) { }
    }
}
