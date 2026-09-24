package hainanMahjong.room;

import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.PlayerController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：真人出牌消息必须能穿过 {@code synchronized (room)} 送达引擎。
 *
 * <p><b>为什么值得单独测</b>：{@code MultiPlayerRoomServiceImpl.onGameMessage} 要在
 * {@code synchronized (room)} 里把玩家的出牌转交 {@code HumanPlayerController}，
 * 而引擎线程此刻正阻塞在 {@code PromptSupport} 的 latch 上等这个应答。
 * 一旦有人给引擎主循环加上"包住整手牌"的 room 锁，
 * 玩家点牌就会被挡在锁外、只能干等 30 秒超时——这类改动很容易顺手写出来，
 * 但四个机器人的冒烟测试<b>根本发现不了</b>（机器人即时应答，不需要跨线程投递）。</p>
 *
 * <p>本测试完全复刻真实路径：真 {@link Room} 对象、{@link Member} 的
 * {@code SwitchingController → HumanPlayerController}、真实
 * {@link hainanMahjong.engine.RoomManager} 引擎循环。</p>
 */
class RoomLockReproTest {

    @Test
    @DisplayName("模拟 onGameMessage 能立刻进入 synchronized(room)，且应答确实推进了引擎")
    void onGameMessageCanAcquireLockWhileEngineWaits() throws Exception {
        Room room = new Room("LOCK01", 1001L);
        room.state = State.PLAYING;

        Member human = new Member(Seat.EAST, 1001L, "真人", null);
        room.members.put(Seat.EAST, human);
        for (Seat s : Seat.values()) {
            if (s == Seat.EAST) continue;
            Member bot = new Member(s, -1L - s.ordinal(), "电脑", null);
            bot.controller.setDelegate(new BotController(20260923L + s.ordinal() * 7919L));
            room.members.put(s, bot);
        }

        // 引擎侧：真人座位用 member.controller（= SwitchingController → HumanPlayerController）
        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);
        for (Seat s : Seat.values()) {
            controllers.put(s, room.members.get(s).controller);
        }

        // 记录引擎发出的第一个询问，供主线程"点牌"用
        final int[] firstReqId = {-1};
        final int[] firstTile = {-1};
        final boolean[] asked = {false};
        human.human.attach(msg -> {
            synchronized (asked) {
                if (!asked[0]) {
                    asked[0] = true;
                    firstReqId[0] = ((Number) msg.get("reqId")).intValue();
                    @SuppressWarnings("unchecked")
                    List<Integer> hand = (List<Integer>) msg.get("hand");
                    firstTile[0] = hand.get(0);
                    asked.notifyAll();
                }
            }
        });

        hainanMahjong.engine.model.GameConfig cfg = new hainanMahjong.engine.model.GameConfig(
                true, Seat.EAST, 30_000L, 15_000L, 20260923L, true, 0, true, 0);

        // 统计引擎实际打出了几张牌，用来证明"应答确实被引擎接住并推进了局面"
        final java.util.concurrent.atomic.AtomicInteger discards =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread engine = new Thread(() -> {
            try {
                // 走真实引擎路径（不自己造锁）
                new hainanMahjong.engine.RoomManager(cfg, controllers, new NoopListener() {
                    @Override
                    public void onDiscard(Seat s, int t) {
                        discards.incrementAndGet();
                    }
                }).start();
            } catch (Throwable t) {
                System.out.println("引擎异常: " + t);
            }
        }, "engine");
        engine.setDaemon(true);
        engine.start();

        // 等引擎发出第一个询问（此时它阻塞在 PromptSupport 的 latch 上）
        synchronized (asked) {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!asked[0] && System.currentTimeMillis() < deadline) {
                asked.wait(200);
            }
        }
        assertTrue(asked[0], "引擎 15 秒内没向真人发出询问");
        assertNotNull(human.human);
        System.out.println("引擎已发出询问 reqId=" + firstReqId[0] + " 首张牌=" + firstTile[0]);

        // ===== 关键测量：复刻 onGameMessage 的三步，全程计时 =====
        long t0 = System.currentTimeMillis();
        final long[] lockWait = {-1};
        Thread ws = new Thread(() -> {
            long t1 = System.currentTimeMillis();
            synchronized (room) {                       // 第 1 步
                lockWait[0] = System.currentTimeMillis() - t1;
                Member m = Member.byId(room, 1001L);    // 第 2 步
                if (m != null && firstReqId[0] > 0) {
                    m.human.discard(firstReqId[0], firstTile[0]);   // 第 3 步
                }
            }
        }, "fake-onGameMessage");
        ws.setDaemon(true);
        ws.start();
        ws.join(3000);
        long total = System.currentTimeMillis() - t0;

        boolean finished = !ws.isAlive();
        System.out.println("模拟 onGameMessage: 完成=" + finished
                + ", 进入锁等待=" + lockWait[0] + "ms, 总耗时=" + total + "ms");

        assertTrue(finished,
                "模拟 onGameMessage 在 3 秒内没能完成 —— 引擎等待真人输入期间占着 room 锁，"
                        + "玩家的出牌消息会被挡在 synchronized(room) 之外，只能等 30 秒超时");

        // 应答被转交后，引擎必须真的往前走（至少再多打出几张牌）。
        // 若应答被丢弃，引擎会一直等到 30 秒超时，这里就不可能达到 2 张。
        long deadline = System.currentTimeMillis() + 3000;
        while (discards.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        System.out.println("出牌转交后引擎已打出 " + discards.get() + " 张（含真人这次应答）");
        assertTrue(discards.get() >= 2,
                "应答交付后引擎没有继续推进（只打出 " + discards.get()
                        + " 张）—— 说明玩家的出牌没有被引擎接住，会退化成一回合卡满超时");

        engine.interrupt();
    }

    private static class NoopListener implements hainanMahjong.engine.port.GameListener {
        public void onShuffle(int d) { }
        public void onDeal(Seat s) { }
        public void onDraw(Seat s, int t) { }
        public void onFlower(Seat s, int t) { }
        public void onDiscard(Seat s, int t) { }
        public void onMeld(Seat s, hainanMahjong.engine.model.Meld m) { }
        public void onHu(Seat s, hainanMahjong.rules.HuResult r, boolean sd, boolean th, int bt, int t, Seat f) { }
        public void onTimeout(Seat s, String a) { }
        public void onRoundDraw() { }
        public void onResume() { }
        public void onEnd(hainanMahjong.engine.model.RoundResult r) { }
    }
}
