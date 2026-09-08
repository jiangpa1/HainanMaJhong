package hainanjong.rules;

import hainanjong.FanType;
import hainanjong.game.Meld;
import hainanjong.game.Seat;

import java.util.ArrayList;
import java.util.List;

/** 规则核心自检（javac 直接跑，不需 Spring）。 */
public class HainanRulesSelfTest {

    static int fail = 0;

    public static void main(String[] args) {
        testGateCases();
        testFanTypes();
        testSettleAndFlow();
        testFlowFull();
        testStage2Settle();
        testStage3();
        System.out.println(fail == 0 ? "\n=== 规则核心自检全部通过 ===" : "\n=== 有 " + fail + " 项失败 ===");
    }

    static void check(String name, boolean cond) {
        if (!cond) {
            fail++;
            System.out.println("FAIL: " + name);
        } else {
            System.out.println("ok  : " + name);
        }
    }

    static int[] build(int[]... pairs) {
        int[] t = new int[34];
        for (int[] p : pairs) {
            t[p[0]] = p[1];
        }
        return t;
    }

    static List<Integer> fl(Integer... v) {
        List<Integer> l = new ArrayList<Integer>();
        for (Integer x : v) {
            l.add(x);
        }
        return l;
    }

    static List<Meld> none() {
        return new ArrayList<Meld>();
    }

    static void testGateCases() {
        // 1) 门清平胡（非258将）点炮应有番：万123,456 + 筒123,456 + 对9筒(17)
        int[] a1 = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{17,2});
        RuleEnv e1 = new RuleEnv(a1, none(), fl(), 0, 0, false, false);
        check("门清平胡点炮有番", HainanFan.legalAndHasFan(e1));
        check("门清平胡倍数=平胡1", HainanFan.multiplierFans(e1).contains(FanType.PING_HU)
                && HainanFan.multiplierSum(e1, HainanConfig.defaultConfig()) == 1);

        // 2) 碰9筒 + 吃? 这里是碰副露，手牌为3顺+将中：非门清、无其它番 → 点炮不能胡
        List<Meld> m2 = new ArrayList<Meld>();
        m2.add(new Meld(Meld.Type.PENG, new int[]{17,17,17}, null));
        int[] conce2 = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{31,2});
        RuleEnv e2 = new RuleEnv(conce2, m2, fl(), 0, 0, false, false);
        check("有碰普通胡点炮【无番不能胡】", !HainanFan.legalAndHasFan(e2));

        // 2b) 同一手 自摸 → 有番
        RuleEnv e2b = new RuleEnv(conce2, m2, fl(), 0, 0, true, false);
        check("同一手自摸则有番", HainanFan.legalAndHasFan(e2b));

        // 3) 2/5/8将（吃123万+碰9万，暗牌=筒123/456 + 对2条(19)）
        List<Meld> m3 = new ArrayList<Meld>();
        m3.add(new Meld(Meld.Type.CHI, new int[]{0,1,2}, null));
        m3.add(new Meld(Meld.Type.PENG, new int[]{8,8,8}, null));
        int[] conce3 = build(new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{19,2});
        RuleEnv e3 = new RuleEnv(conce3, m3, fl(), 0, 0, false, false);
        check("2/5/8将做眼算有番", HainanFan.gate(e3).eye258 && HainanFan.legalAndHasFan(e3));

        // 4) 令风刻：南令(1)、吃123万，暗含南(28)刻 + 筒123/456 + 7筒对(15)
        List<Meld> m4 = new ArrayList<Meld>();
        m4.add(new Meld(Meld.Type.CHI, new int[]{0,1,2}, null));
        int[] conce4 = build(new int[]{28,3}, new int[]{9,1}, new int[]{10,1}, new int[]{11,1},
                new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{15,2});
        RuleEnv e4 = new RuleEnv(conce4, m4, fl(), 0, 1, false, false);
        check("南令南风刻有番", HainanFan.gate(e4).roundWindK && HainanFan.legalAndHasFan(e4));

        // 5) 座位风刻：东位(offset0) 吃筒123 + 暗含东(27)刻 + 万123/456 + 7万对(6)
        List<Meld> m5 = new ArrayList<Meld>();
        m5.add(new Meld(Meld.Type.CHI, new int[]{9,10,11}, null));
        int[] conce5 = build(new int[]{27,3}, new int[]{0,1}, new int[]{1,1}, new int[]{2,1},
                new int[]{3,1}, new int[]{4,1}, new int[]{5,1}, new int[]{6,2});
        RuleEnv e5 = new RuleEnv(conce5, m5, fl(), 0, 0, false, false);
        check("东位东刻有番", HainanFan.gate(e5).seatWindK && HainanFan.legalAndHasFan(e5));

        // 6) 位置花：下家(offset1) 抓到 夏(35)：万123/456 + 筒123/456 + 7筒对(15)
        int[] a6 = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{15,2});
        RuleEnv e6 = new RuleEnv(a6, none(), fl(35), 1, 0, false, false);
        check("抓到位置花有番", HainanFan.gate(e6).posFlower);

        // 7) 抢杠胡算有番
        RuleEnv e7 = new RuleEnv(conce2, m2, fl(), 0, 0, false, true);
        check("抢杠胡算有番", HainanFan.gate(e7).qiangGangFan && HainanFan.legalAndHasFan(e7));
    }

    static void testFanTypes() {
        // 清一色七对
        int[] q7 = build(new int[]{0,2}, new int[]{1,2}, new int[]{2,2}, new int[]{3,2}, new int[]{4,2}, new int[]{5,2}, new int[]{6,2});
        RuleEnv eq7 = new RuleEnv(q7, none(), fl(), 0, 0, true, false);
        List<FanType> fq7 = HainanFan.multiplierFans(eq7);
        check("清一色七对=七对+清一色", fq7.contains(FanType.QI_DUI) && fq7.contains(FanType.QING_YI_SE));

        // 龙七对：1万x4 + 东南西北中 各一对
        int[] lq = build(new int[]{0,4}, new int[]{27,2}, new int[]{28,2}, new int[]{29,2}, new int[]{30,2}, new int[]{31,2});
        RuleEnv elq = new RuleEnv(lq, none(), fl(), 0, 0, true, false);
        List<FanType> flq = HainanFan.multiplierFans(elq);
        check("龙七对覆盖七对", flq.contains(FanType.LONG_QI_DUI) && !flq.contains(FanType.QI_DUI));
        check("龙七对与混一色并存", flq.contains(FanType.HUN_YI_SE));

        // 清一色普通胡：只清一色，不加平胡
        int[] qing = build(new int[]{0,3}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{6,1}, new int[]{7,2}, new int[]{8,3});
        RuleEnv eq = new RuleEnv(qing, none(), fl(), 0, 0, true, false);
        List<FanType> fq = HainanFan.multiplierFans(eq);
        check("清一色普通胡=清一色(无平胡)", fq.contains(FanType.QING_YI_SE) && !fq.contains(FanType.PING_HU));

        // 平胡手：普通多花色、无特殊 → 平胡兜底
        int[] ph = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{17,2});
        RuleEnv eph = new RuleEnv(ph, none(), fl(), 0, 0, true, false);
        check("普通胡(无特殊)平胡兜底1", HainanFan.multiplierFans(eph).contains(FanType.PING_HU)
                && HainanFan.multiplierSum(eph, HainanConfig.defaultConfig()) == 1);

        // 十三幺倍数13
        int[] s13 = build(new int[]{0,1}, new int[]{8,1}, new int[]{9,1}, new int[]{17,1}, new int[]{18,1}, new int[]{26,1},
                new int[]{27,1}, new int[]{28,1}, new int[]{29,1}, new int[]{30,1}, new int[]{31,2}, new int[]{32,1}, new int[]{33,1});
        RuleEnv es13 = new RuleEnv(s13, none(), fl(), 0, 0, true, false);
        check("十三幺13倍", HainanFan.multiplierSum(es13, HainanConfig.defaultConfig()) == 13);
    }

    // ==================== 结算 & 庄流 ====================

    static java.util.List<Integer> handList(int[] t34) {
        java.util.List<Integer> l = new ArrayList<Integer>();
        for (int i = 0; i < 34; i++) {
            for (int k = 0; k < t34[i]; k++) {
                l.add(i);
            }
        }
        java.util.Collections.sort(l);
        return l;
    }

    static void testSettleAndFlow() {
        HainanConfig cfg = HainanConfig.defaultConfig();
        // 平胡手（非258）：万123/456 筒123/456 9筒将
        int[] ping = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{17,2});
        java.util.List<Integer> ph = handList(ping);
        java.util.List<Meld> none = new ArrayList<Meld>();

        // A) 庄底分2，庄(EAST)点炮给闲(SOUTH)，平胡无杠 → 庄 -3，另两闲各 -1，赢家 +5
        hainanjong.game.RoundResult ra = hainanjong.game.RoundResult.win(
                Seat.SOUTH, false, false, 17, Seat.EAST, null, ph, none);
        java.util.Map<Seat, Integer> da = HainanScore.settle(ra, Seat.EAST, 2, cfg);
        check("A 庄付3", da.get(Seat.EAST) == -3);
        check("A 两闲各付1", da.get(Seat.WEST) == -1 && da.get(Seat.NORTH) == -1);
        check("A 赢家收5", da.get(Seat.SOUTH) == 5);

        // B) 庄底分2，闲(SOUTH)自摸平胡 → 庄付4，两闲各付2，赢家收8
        hainanjong.game.RoundResult rb = hainanjong.game.RoundResult.win(
                Seat.SOUTH, true, false, -1, null, null, ph, none);
        java.util.Map<Seat, Integer> db = HainanScore.settle(rb, Seat.EAST, 2, cfg);
        check("B 庄付4", db.get(Seat.EAST) == -4);
        check("B 闲各付2", db.get(Seat.WEST) == -2 && db.get(Seat.NORTH) == -2);
        check("B 赢家收8", db.get(Seat.SOUTH) == 8);

        // C) 流局不结算
        hainanjong.game.RoundResult rc = hainanjong.game.RoundResult.draw();
        java.util.Map<Seat, Integer> dc = HainanScore.settle(rc, Seat.EAST, 2, cfg);
        int zero = dc.get(Seat.EAST) + dc.get(Seat.SOUTH) + dc.get(Seat.WEST) + dc.get(Seat.NORTH);
        check("C 流局0", zero == 0);

        // D) 庄流：EAST首庄；EAST输给SOUTH → 下庄 new=SOUTH,底分重置1,令仍东；SOUTH连赢→底分2令不变
        DealerFlow flow = new DealerFlow(cfg, Seat.EAST);
        check("D 首把底分=配置1", flow.bottom == 1 && flow.windIdx == 0);
        flow.afterRound(hainanjong.game.RoundResult.win(Seat.SOUTH, false, false, 0, Seat.EAST, null, null, null));
        check("D 下庄给SOUTH", flow.dealer == Seat.SOUTH && flow.bottom == 1 && flow.windIdx == 0);
        flow.afterRound(hainanjong.game.RoundResult.win(Seat.SOUTH, true, false, -1, null, null, null, null));
        check("D 庄自摸连庄底分+1", flow.dealer == Seat.SOUTH && flow.bottom == 2 && flow.windIdx == 0);

        // E) 乘算底分：初始底分 2 → 连庄后 4/6/8（=底分×(连庄次数+1)），下庄回 2
        HainanConfig cfg2 = HainanConfig.defaultConfig();
        cfg2.basePoint = 2;
        DealerFlow f2 = new DealerFlow(cfg2, Seat.EAST);
        check("E 首把底分=2", f2.bottom == 2 && f2.consecutiveKeeps() == 0);
        f2.afterRound(hainanjong.game.RoundResult.win(Seat.SOUTH, false, false, 0, Seat.EAST, null, null, null));
        check("E 下庄给SOUTH 底分回2", f2.dealer == Seat.SOUTH && f2.bottom == 2);
        f2.afterRound(hainanjong.game.RoundResult.win(Seat.SOUTH, true, false, -1, null, null, null, null));
        check("E 连庄1 底分4", f2.bottom == 4 && f2.consecutiveKeeps() == 1);
        f2.afterRound(hainanjong.game.RoundResult.win(Seat.SOUTH, true, false, -1, null, null, null, null));
        check("E 连庄2 底分6", f2.bottom == 6 && f2.consecutiveKeeps() == 2);
        f2.afterRound(hainanjong.game.RoundResult.draw());
        check("E 流局连庄 底分8", f2.dealer == Seat.SOUTH && f2.bottom == 8 && f2.consecutiveKeeps() == 3);
    }

    // ==================== 四风整局仿真（应总能结束且令推进到北） ====================

    static void testFlowFull() {
        java.util.Random rnd = new java.util.Random(7L);
        int trials = 60;
        for (int t = 0; t < trials; t++) {
            HainanConfig cfg = HainanConfig.defaultConfig();
            Seat first = Seat.values()[rnd.nextInt(4)];
            DealerFlow f = new DealerFlow(cfg, first);
            int steps = 0, maxWind = 0;
            while (!f.finished && steps < 500) {
                steps++;
                maxWind = Math.max(maxWind, f.windIdx);
                int kind = rnd.nextInt(4);
                if (kind == 0) {
                    f.afterRound(hainanjong.game.RoundResult.draw());
                } else {
                    Seat w = Seat.values()[rnd.nextInt(4)];
                    boolean self = rnd.nextBoolean();
                    f.afterRound(hainanjong.game.RoundResult.win(w, self, false, -1, null, null, null, null));
                }
            }
            check("四风仿真 #" + t + " 能在合理把数内结束(steps=" + steps + ")", f.finished && steps < 500);
            check("四风仿真 #" + t + " 令已到过北(西/北)", maxWind >= 3);
            if (!f.finished) {
                return;
            }
        }
    }

    static void testStage2Settle() {
        HainanConfig cfg = HainanConfig.defaultConfig();
        java.util.Map<Seat, List<Integer>> fl = new java.util.EnumMap<Seat, List<Integer>>(Seat.class);
        java.util.Map<Seat, List<Meld>> mds = new java.util.EnumMap<Seat, List<Meld>>(Seat.class);
        for (Seat s : Seat.values()) {
            fl.put(s, new ArrayList<Integer>());
            mds.put(s, new ArrayList<Meld>());
        }
        mds.get(Seat.SOUTH).add(new Meld(Meld.Type.AN_GANG, new int[]{27,27,27,27}, null));
        fl.get(Seat.SOUTH).add(34);
        fl.get(Seat.SOUTH).add(35);
        fl.get(Seat.SOUTH).add(36);
        fl.get(Seat.SOUTH).add(37);
        int[] ping = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{17,2});
        java.util.List<Integer> ph = handList(ping);
        hainanjong.game.RoundResult r = hainanjong.game.RoundResult.win(
                Seat.NORTH, true, false, -1, null, null, ph, new ArrayList<Meld>());

        HainanScore.Settlement st = HainanScore.settleRound(r, Seat.EAST, 1, cfg, fl, mds, 40, false, null);
        int sum = 0;
        for (Seat s : Seat.values()) {
            sum += st.delta.get(s);
        }
        check("杠钱+花分 全桌零和", sum == 0);
        check("南家暗杠2×3+真花1×3-胡付2=+7", st.delta.get(Seat.SOUTH) == 7);
        check("北家自摸收6-杠钱2-花1=+3", st.delta.get(Seat.NORTH) == 3);
        boolean gang = false, trueFlower = false;
        for (String n : st.notes.get(Seat.SOUTH)) {
            if (n.startsWith("暗杠×")) {
                gang = true;
            }
            if (n.contains("真花")) {
                trueFlower = true;
            }
        }
        check("南家有杠与花明细", gang && trueFlower);
    }

    static void testStage3() {
        HainanConfig cfg = HainanConfig.defaultConfig();
        int[] ping = build(new int[]{0,1}, new int[]{1,1}, new int[]{2,1}, new int[]{3,1}, new int[]{4,1}, new int[]{5,1},
                new int[]{9,1}, new int[]{10,1}, new int[]{11,1}, new int[]{12,1}, new int[]{13,1}, new int[]{14,1}, new int[]{17,2});
        java.util.List<Integer> ph = handList(ping);
        hainanjong.game.RoundResult r1 = hainanjong.game.RoundResult.winFull(
                Seat.EAST, true, false, true, 1, -1, null, null, ph, new ArrayList<Meld>());
        java.util.List<FanType> f1 = HainanScore.multFans(r1);
        check("天胡+天听 计入番型", f1.contains(FanType.TIAN_HU) && f1.contains(FanType.TIAN_TING));
        int sum1 = HainanScore.multSum(r1, cfg);
        check("平胡1+天胡5+天听4=10", sum1 == 10);

        hainanjong.game.RoundResult r2 = hainanjong.game.RoundResult.winFull(
                Seat.WEST, false, false, false, 2, 17, Seat.NORTH, null, ph, new ArrayList<Meld>());
        check("地听(点炮)计入", HainanScore.multFans(r2).contains(FanType.DI_TING));
    }
}
