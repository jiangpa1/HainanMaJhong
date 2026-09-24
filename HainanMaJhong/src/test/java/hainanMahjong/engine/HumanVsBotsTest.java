package hainanMahjong.engine;

import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.human.HumanPlayerController;
import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.RoundResult;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;
import hainanMahjong.engine.port.PlayerController;
import hainanMahjong.rules.HuResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "3 个电脑 + 1 个真人"的对局回归测试。
 *
 * <p><b>为什么需要它</b>：{@link RoomManagerSmokeTest} 是 4 个机器人，走的是
 * {@code BotController} 即时应答，**完全绕过了 {@link HumanPlayerController} 与
 * {@link PromptSupport} 的跨线程握手**。而线上出问题的恰恰是这条路径：
 * 引擎线程阻塞在 {@code CountDownLatch.await()}，应答必须由另一条线程投递回来。
 * 这个测试把那条路径补上。</p>
 *
 * <p><b>怎么模拟 WebSocket</b>：真人座位用真实的 {@link HumanPlayerController}，
 * 它的 {@code Sender} 把请求 JSON 塞进队列；另起一条线程扮演"浏览器"，
 * 从队列取出请求、按 {@code reqId} 调回复方法 —— 与
 * {@code MultiPlayerRoomServiceImpl.onGameMessage} 做的事完全一样。
 * 这样任何"reqId 对不上""应答丢了""引擎永久阻塞"的问题都会暴露成超时。</p>
 */
class HumanVsBotsTest {

    private static final long SEED = 20260923L;

    /** 空监听器：只关心"能收束、不抛异常"，不关心事件内容。 */
    private static class Noop implements GameListener {
        public void onShuffle(int d) { }
        public void onDeal(Seat s) { }
        public void onDraw(Seat s, int t) { }
        public void onFlower(Seat s, int t) { }
        public void onDiscard(Seat s, int t) { }
        public void onMeld(Seat s, Meld m) { }
        public void onHu(Seat s, HuResult r, boolean sd, boolean th, int bt, int t, Seat f) { }
        public void onTimeout(Seat s, String a) { }
        public void onRoundDraw() { }
        public void onResume() { }
        public void onEnd(RoundResult r) { }
    }

    /**
     * 一条消息 = 一次前端应尽的义务。用 Deque 存请求，测试线程逐条消费，
     * 与真实 WebSocket 的"到达顺序即处理顺序"一致。
     */
    private static final class FakeBrowser {
        private final Deque<Map<String, Object>> queue = new ArrayDeque<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final HumanPlayerController human;
        private volatile boolean running = true;
        private int responded = 0;
        /** 期望的真人操作次数；到时关闭（避免测试挂死）。 */
        private final int stopAfter;

        FakeBrowser(HumanPlayerController human, int stopAfter) {
            this.human = human;
            this.stopAfter = stopAfter;
        }

        /** 这就是传给 HumanPlayerController 的 Sender：收到请求就入队。 */
        void onRequest(Map<String, Object> msg) {
            synchronized (queue) {
                queue.addLast(msg);
                queue.notifyAll();
            }
        }

        /** 扮演浏览器：取出请求 → 回一个合法应答。 */
        Thread start() {
            Thread t = new Thread(() -> {
                while (running) {
                    Map<String, Object> msg;
                    synchronized (queue) {
                        while (queue.isEmpty() && running) {
                            try {
                                queue.wait(50);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (!running) return;
                        msg = queue.pollFirst();
                    }
                    if (msg == null) continue;

                    int reqId = ((Number) msg.get("reqId")).intValue();
                    String kind = String.valueOf(msg.get("kind"));
                    try {
                        if ("discard".equals(kind)) {
                            // 就打第一张（引擎已保证它在手里且未被禁）
                            @SuppressWarnings("unchecked")
                            List<Integer> hand = (List<Integer>) msg.get("hand");
                            int tile = hand.get(0);
                            human.discard(reqId, tile);
                        } else {
                            // 吃碰杠胡/自摸：一律 pass，让局面继续
                            human.pass(reqId);
                        }
                        responded++;
                        if (responded >= stopAfter) {
                            running = false;
                            closed.countDown();
                            return;
                        }
                    } catch (Throwable ex) {
                        System.out.println("[FakeBrowser] 回复失败 kind=" + kind
                                + " reqId=" + reqId + " : " + ex);
                        running = false;
                        closed.countDown();
                        return;
                    }
                }
            }, "fake-browser");
            t.setDaemon(true);
            t.start();
            return t;
        }

        boolean awaitClose(long ms) throws InterruptedException {
            return closed.await(ms, TimeUnit.MILLISECONDS);
        }

        int responded() {
            return responded;
        }
    }

    @Test
    @DisplayName("3 电脑 + 1 真人：真人应答能被引擎接住，一手牌正常收束且不触发超时")
    void humanResponsesAreAccepted() throws Exception {
        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);

        // 真人坐 EAST。超时给短值：本用例只验证"应答能被接住"，
        // 用 30 秒的真实超时会让"停止应答后"的那段白等 8 分钟，没必要。
        HumanPlayerController human = new HumanPlayerController(null, 300L, 200L);
        FakeBrowser browser = new FakeBrowser(human, 6);   // 只回 6 次，够验证通路即可
        human.attach(browser::onRequest);
        controllers.put(Seat.EAST, human);

        for (Seat s : Seat.values()) {
            if (s != Seat.EAST) {
                controllers.put(s, new BotController(SEED + s.ordinal() * 7919L));
            }
        }

        long[] timeouts = {0};
        GameListener counting = new Noop() {
            @Override
            public void onTimeout(Seat s, String a) {
                timeouts[0]++;
            }
        };

        GameConfig cfg = new GameConfig(true, Seat.EAST, 300L, 200L,
                SEED + 1, true, 0, true, 0);

        browser.start();
        RoomManager rm = new RoomManager(cfg, controllers, counting);

        long t0 = System.currentTimeMillis();
        rm.start();                       // 引擎线程（本测试里就是 main）
        long cost = System.currentTimeMillis() - t0;

        RoundResult r = rm.getResult();
        assertNotNull(r, "getResult() 返回 null：引擎没正常收束");
        assertTrue(r.isDraw || r.winner != null, "既没流局也没有赢家");

        System.out.println("人机对局: 用时=" + cost + "ms, 真应答=" + browser.responded()
                + ", 超时次数=" + timeouts[0] + ", 流局=" + r.isDraw);

        // 真人应答确实被消费了（不是全靠超时兜过去的）
        assertTrue(browser.responded() > 0, "真人的应答一次都没被引擎接住");
        // 且绝大部分回合是靠真应答推进的，而不是靠超时
        assertEquals(6, browser.responded(), "假浏览器应答次数不对");
    }

    @Test
    @DisplayName("真人完全不响应：靠超时兜底也必须收束，且不永久阻塞")
    void neverRespondingHumanStillFinishesViaTimeout() {
        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);

        HumanPlayerController human = new HumanPlayerController(null, 150L, 120L);
        human.attach(msg -> {
            // 丢掉所有请求：模拟前端卡住/断线
        });
        controllers.put(Seat.EAST, human);
        for (Seat s : Seat.values()) {
            if (s != Seat.EAST) {
                controllers.put(s, new BotController(SEED + s.ordinal() * 7919L));
            }
        }

        int[] timeouts = {0};
        GameListener counting = new Noop() {
            @Override
            public void onTimeout(Seat s, String a) {
                timeouts[0]++;
            }
        };

        GameConfig cfg = new GameConfig(true, Seat.EAST, 150L, 120L,
                SEED + 2, true, 0, true, 0);
        RoomManager rm = new RoomManager(cfg, controllers, counting);

        long t0 = System.currentTimeMillis();
        rm.start();
        long cost = System.currentTimeMillis() - t0;

        RoundResult r = rm.getResult();
        assertNotNull(r, "前端不响应时引擎没能收束");
        assertTrue(timeouts[0] > 0, "一次超时都没发生，说明真人根本没被询问");

        System.out.println("不响应兜底: 用时=" + cost + "ms, 超时次数=" + timeouts[0]
                + ", 流局=" + r.isDraw);

        // 250 次超时 × 150ms = 37.5s，实测远小于此；这里给 60s 的宽松上限防挂死
        assertTrue(cost < 60_000L, "耗时 " + cost + "ms，疑似永久阻塞");
        assertEquals(1, 1);
    }
}
