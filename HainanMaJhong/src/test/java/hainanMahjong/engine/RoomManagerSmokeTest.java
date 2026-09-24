package hainanMahjong.engine;

import hainanMahjong.rules.HuResult;
import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.RoundResult;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;
import hainanMahjong.engine.port.PlayerController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎回归测试：4 个机器人跑满 400 把海南规则，验证"引擎能正常收束且不抛异常"。
 *
 * <p><b>为什么需要它</b>：{@link RoomManager} 是引擎核心，里面有 16 处
 * {@code config.hainan} 分支，任何一次改动都可能让某一局卡死或抛异常。
 * 这个测试就是那种"改坏了立刻能发现"的安全网。</p>
 *
 * <p><b>为什么是 400 把</b>：单局很快，但只有跑量足够大才会碰到
 * 抢杠、杠后补花、荒庄流局、报听锁手这些边角路径。400 把大约覆盖：正常胡、流局、抢杠。</p>
 *
 * <p><b>可复现性</b>：洗牌用 {@code config.seed}，机器人用固定种子的
 * {@link BotController#BotController(long)}，所以<b>整局完全确定</b>——
 * 同样的代码跑出来统计数字必须一模一样。重构后若数字变了，说明行为被改了。</p>
 *
 * <p>本类由原 {@code engine/dev/EngineSmokeTest} 迁移而来（原来放在 {@code src/main}
 * 里靠手工 javac 跑，现已改为正式单测）。</p>
 */
class RoomManagerSmokeTest {

    /** 一把都不许出错。 */
    private static final int HANDS = 400;

    /** 空监听器：只关心"不抛异常、能收束"，不关心事件内容。 */
    private static final class Noop implements GameListener {
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

    /** 跑一把海南规则（含花 144 张），返回结果；异常直接让测试失败。 */
    private RoundResult playOneHand(long seed, Seat dealer, int roundWind) {
        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);
        for (Seat s : Seat.values()) {
            // 给每个座位一个确定但互不相同的种子
            controllers.put(s, new BotController(seed + s.ordinal() * 7919L));
        }
        GameConfig cfg = new GameConfig(
                true,               // withFlowers：海南 144 张
                dealer,
                2000L, 1500L,       // 超时给足，机器人是即时应答
                seed,
                true,               // chiOnlyNext
                0,                  // maxTurns：不限
                true,               // hainan：启用海南规则
                roundWind);
        RoomManager rm = new RoomManager(cfg, controllers, new Noop());
        rm.start();
        return rm.getResult();
    }

    @Test
    @DisplayName("海南规则 4 机器人 400 把：零异常、零空结果、胡+流局=400")
    void hainanFourBots400Hands() {
        int errors = 0;
        int wins = 0;
        int draws = 0;
        int qiangGangs = 0;

        for (int h = 0; h < HANDS; h++) {
            long seed = 2026L + h;
            Seat dealer = Seat.values()[h % 4];
            int roundWind = h % 4;
            try {
                RoundResult r = playOneHand(seed, dealer, roundWind);
                assertNotNull(r, "第 " + h + " 把：getResult() 返回 null（引擎没正常收束）");
                if (r.isDraw) {
                    draws++;
                } else {
                    wins++;
                    if (r.qiangGang) {
                        qiangGangs++;
                    }
                }
            } catch (Throwable t) {
                errors++;
                // 把第一手的异常完整抛出来，便于定位
                if (errors == 1) {
                    throw new AssertionError("第 " + h + " 把抛异常（seed=" + seed + "）", t);
                }
            }
        }

        System.out.println("海南引擎回归: " + HANDS + " 把, 错误=" + errors
                + ", 胡=" + wins + ", 流局=" + draws + ", 抢杠=" + qiangGangs);

        // 把统计也写一份到文件：surefire 有时不保留 System.out，写到文件才能可靠比对
        try {
            String line = "hands=" + HANDS + " errors=" + errors + " wins=" + wins
                    + " draws=" + draws + " qiangGangs=" + qiangGangs;
            java.nio.file.Files.write(
                    java.nio.file.Paths.get("target", "engine-regression.txt"),
                    (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 写文件失败不影响测试结论
        }

        assertEquals(0, errors, "有 " + errors + " 把抛了异常");
        assertEquals(HANDS, wins + draws, "每把都必须以「胡」或「流局」收束");
        assertTrue(wins > 0, "400 把里一把都没胡，引擎或规则有问题");
        assertTrue(wins + draws == HANDS, "跑满的把数应等于 " + HANDS + "，实际 " + (wins + draws));
    }
}
