package hainanjong.game;

import hainanjong.HuResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/** 引擎冒烟：海南规则开启 + 4 Bot，随机跑多把，验证不抛异常且能正常收束。 */
public class EngineSmokeTest {

    static class Noop implements GameListener {
        public void onShuffle(int d) {}
        public void onDeal(Seat s) {}
        public void onDraw(Seat s, int t) {}
        public void onFlower(Seat s, int t) {}
        public void onDiscard(Seat s, int t) {}
        public void onMeld(Seat s, Meld m) {}
        public void onHu(Seat s, HuResult r, boolean sd, int t, Seat f) {}
        public void onTimeout(Seat s, String a) {}
        public void onRoundDraw() {}
        public void onResume() {}
        public void onEnd(RoundResult r) {}
    }

    public static void main(String[] args) {
        Random rnd = new Random(2026L);
        int hands = 400;
        int err = 0;
        int wins = 0, draws = 0, qiangs = 0;
        for (int h = 0; h < hands; h++) {
            try {
                Seat dealer = Seat.values()[rnd.nextInt(4)];
                int rw = rnd.nextInt(4);
                Map<Seat, PlayerController> cs = new EnumMap<Seat, PlayerController>(Seat.class);
                for (Seat s : Seat.values()) {
                    cs.put(s, new BotController());
                }
                GameConfig cfg = new GameConfig(true, dealer, 2000L, 1500L,
                        rnd.nextLong(), true, 0, true, rw);
                RoomManager rm = new RoomManager(cfg, cs, new Noop());
                rm.start();
                RoundResult r = rm.getResult();
                if (r == null) {
                    err++;
                    System.out.println("hand " + h + ": result null");
                    continue;
                }
                if (r.isDraw) {
                    draws++;
                } else {
                    wins++;
                    if (r.qiangGang) {
                        qiangs++;
                    }
                }
            } catch (Throwable t) {
                err++;
                System.out.println("hand " + h + " exception: " + t);
                t.printStackTrace(System.out);
            }
        }
        System.out.println("海南引擎冒烟: " + hands + " 把, 错误=" + err
                + ", 胡=" + wins + ", 流局=" + draws + ", 抢杠=" + qiangs);
        System.out.println(err == 0 ? "=== 冒烟通过 ===" : "=== 有异常 ===");
    }
}
