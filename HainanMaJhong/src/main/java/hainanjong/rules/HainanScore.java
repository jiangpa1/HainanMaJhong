package hainanjong.rules;

import hainanjong.FanType;
import hainanjong.game.Meld;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 海南规则结算（阶段1：只含胡牌本身，不含杠钱/花分/包牌——这些在阶段2/3）。
 *
 * <p>要点（已与用户确认口径）：</p>
 * <ul>
 *   <li>输赢“每家一笔”都付赢家：胡牌（含点炮/抢杠）与自摸一样三家都付；</li>
 *   <li>每笔倍数：这笔含“庄”（赢家或付家是庄）→ 按当前庄底分乘；纯闲↔闲 → 乘 1；</li>
 *   <li>基础分取大不叠加：胡 1 / 自摸 2 / 杠开 3（杠开含自摸取 3）；</li>
 *   <li>番型倍数：命中的番型相加；平胡只在无其它番型时兜底 = 1；龙七对覆盖七对；</li>
 *   <li>点炮者（放杠/放牌的付家）在自已那笔之外另赔固定 1 分（不乘底分/倍数）；</li>
 *   <li>流局不结算（荒庄连庄由 DealerFlow 处理；杠牌流局默认不算分）。</li>
 * </ul>
 */
public final class HainanScore {

    private HainanScore() {
    }

    /** 基础分：胡 1 / 自摸 2 / 杠开 3；流局 0。 */
    public static int basePoints(RoundResult r) {
        if (r.isDraw) {
            return 0;
        }
        return r.ganKai ? 3 : (r.selfDraw ? 2 : 1);
    }

    /** 结算番型（从胡牌手牌重算，保证 平胡兜底/龙七对覆盖/天胡天听地听 等新口径）。 */
    public static List<FanType> multFans(RoundResult r) {
        if (r.isDraw || r.winner == null) {
            return new ArrayList<FanType>();
        }
        List<FanType> fans = new ArrayList<FanType>(HainanFan.multiplierFans(envOf(r)));
        if (r.tianHu) {
            fans.add(FanType.TIAN_HU);
        }
        if (r.winnerBaoTing == 1) {
            fans.add(FanType.TIAN_TING);
        } else if (r.winnerBaoTing == 2) {
            fans.add(FanType.DI_TING);
        }
        return fans;
    }

    /** 番型倍数和。 */
    public static int multSum(RoundResult r, HainanConfig cfg) {
        int s = 0;
        for (FanType f : multFans(r)) {
            s += cfg.multiplier(f);
        }
        return s;
    }

    /** 四家本把输赢（正=赢，负=输）。 */
    public static Map<Seat, Integer> settle(RoundResult r, Seat dealer, int bottom, HainanConfig cfg) {
        Map<Seat, Integer> delta = new EnumMap<Seat, Integer>(Seat.class);
        for (Seat s : Seat.values()) {
            delta.put(s, 0);
        }
        if (r.isDraw || r.winner == null) {
            return delta;
        }
        Seat winner = r.winner;
        int K = basePoints(r) * multSum(r, cfg); // 阶段1额外分=0（杠钱阶段2）
        for (Seat s : Seat.values()) {
            if (s == winner) {
                continue;
            }
            int factor = (s == dealer || winner == dealer) ? bottom : cfg.basePoint;
            int pay = factor * K;
            if (r.from != null && s == r.from) {
                pay += cfg.basePoint; // 点炮者追加：始终房间初始底分
            }
            delta.put(s, delta.get(s) - pay);
            delta.put(winner, delta.get(winner) + pay);
        }
        return delta;
    }

    /** 由 RoundResult 构造规则上下文（只需形状判定用）。 */
    static RuleEnv envOf(RoundResult r) {
        int[] counts = new int[34];
        if (r.winHand != null) {
            for (int t : r.winHand) {
                if (t >= 0 && t < 34) {
                    counts[t]++;
                }
            }
        }
        List<Meld> melds = r.winMelds == null ? new ArrayList<Meld>() : r.winMelds;
        return new RuleEnv(counts, melds, new ArrayList<Integer>(), 0, 0, false, false);
    }

    /** 按“暗牌手牌 + 胡到的那张 + 副露 + 花”直接算结算番型（广播/展示用）。 */
    public static List<FanType> fansOfHand(List<Integer> hand, List<Meld> melds,
                                           List<Integer> flowers, int extraWinTile) {
        int[] counts = new int[34];
        if (hand != null) {
            for (int t : hand) {
                if (t >= 0 && t < 34) {
                    counts[t]++;
                }
            }
        }
        if (extraWinTile >= 0 && extraWinTile < 34) {
            counts[extraWinTile]++;
        }
        List<Meld> m = melds == null ? new ArrayList<Meld>() : melds;
        List<Integer> f = flowers == null ? new ArrayList<Integer>() : flowers;
        return HainanFan.multiplierFans(new RuleEnv(counts, m, f, 0, 0, false, false));
    }

    // ==================== 阶段2：杠钱/花分/包牌代付 完整结算 ====================

    /** 一局的完整结算：四家输赢 + 每家明细标签（供战绩页逐局展示杠/花/胡）。 */
    public static final class Settlement {
        public final Map<Seat, Integer> delta;
        public final Map<Seat, List<String>> notes;

        Settlement(Map<Seat, Integer> delta, Map<Seat, List<String>> notes) {
            this.delta = delta;
            this.notes = notes;
        }
    }

    /**
     * 完整结算（海南阶段2）：
     * <ul>
     *   <li>胡牌：三家都付、含庄翻倍、点炮者 +1（与 {@link #settle} 一致）；</li>
     *   <li>包牌代付：三道(关系≥3)点炮 / 四道(关系≥4)自摸 / 尾墙≤19点炮 / 抢杠无其它番 → 由一方代付三家；</li>
     *   <li>杠钱：三种杠都三家各付一份（明1/补1/暗2），含庄翻倍；流局不计；包杠(庄首张被明杠)由庄代付该杠三家份；</li>
     *   <li>花分：真/假/真对/假对各自累加，三家各付且含庄翻倍；流局不计；</li>
     *   <li>跟牌：发生即付（庄输另三家各底分），流局也算。</li>
     * </ul>
     */
    /** 杠单位分：明杠/补杠/暗杠（房间可设）。 */
    private static int gangUnit(HainanConfig cfg, Meld.Type t) {
        if (t == Meld.Type.BU_GANG) {
            return cfg.gangBu;
        }
        if (t == Meld.Type.AN_GANG) {
            return cfg.gangAn;
        }
        return cfg.gangMing;
    }

    public static Settlement settleRound(RoundResult r, Seat dealer, int bottom, HainanConfig cfg,
                                         Map<Seat, List<Integer>> flowers, Map<Seat, List<Meld>> melds,
                                         int wallLeft, boolean genChain, Seat dealerFirstGangSeat) {
        Map<Seat, Integer> delta = new EnumMap<Seat, Integer>(Seat.class);
        Map<Seat, List<String>> notes = new EnumMap<Seat, List<String>>(Seat.class);
        for (Seat s : Seat.values()) {
            delta.put(s, 0);
            notes.put(s, new ArrayList<String>());
        }
        if (r == null) {
            return new Settlement(delta, notes);
        }

        // 跟牌：庄输另三家各 底分（发生即付，流局也算）
        if (genChain && dealer != null) {
            for (Seat s : Seat.values()) {
                if (s != dealer) {
                    delta.put(s, delta.get(s) + bottom);
                    delta.put(dealer, delta.get(dealer) - bottom);
                }
            }
            notes.get(dealer).add("跟牌");
        }

        boolean counted = !r.isDraw; // 流局：杠钱/花分/胡分不计
        if (!counted) {
            return new Settlement(delta, notes);
        }
        if (r.winner == null) {
            return new Settlement(delta, notes);
        }

        Seat winner = r.winner;
        int K = basePoints(r) * multSum(r, cfg);
        List<Seat> losers = new ArrayList<Seat>();
        for (Seat s : Seat.values()) {
            if (s != winner) {
                losers.add(s);
            }
        }

        // 各笔应付（先算，用于判断是否有人代付三家）
        int total = 0;
        Seat responsible = null;
        if (r.from != null) {
            boolean tail = wallLeft >= 0 && wallLeft <= 19;           // 墙剩≤19 点炮
            boolean rel3 = relationAtLeast(winner, r.from, melds, 3);  // 三道
            boolean qnf = r.qiangGang && qiangNoOtherFan(r);           // 抢杠且无其它番
            if (tail || rel3 || qnf) {
                responsible = r.from;
            }
        } else {
            for (Seat p : losers) {
                if (relationAtLeast(winner, p, melds, 4)) {            // 四道自摸
                    responsible = p;
                    break;
                }
            }
        }

        Map<Seat, Integer> perLoser = new EnumMap<Seat, Integer>(Seat.class);
        for (Seat s : losers) {
            // 庄相关笔→当前庄底分(连庄增长)；纯闲↔闲→房间初始底分
            int factor = (s == dealer || winner == dealer) ? bottom : cfg.basePoint;
            int pay = factor * K;
            if (r.from != null && s == r.from) {
                pay += cfg.basePoint; // 点炮者追加：始终房间初始底分
            }
            perLoser.put(s, pay);
            total += pay;
        }

        if (responsible == null) {
            for (Seat s : losers) {
                int pay = perLoser.get(s);
                delta.put(s, delta.get(s) - pay);
                delta.put(winner, delta.get(winner) + pay);
            }
        } else {
            delta.put(responsible, delta.get(responsible) - total);
            delta.put(winner, delta.get(winner) + total);
            notes.get(responsible).add("代付三家");
        }

        // 胡牌者明细
        List<FanType> fans = multFans(r);
        StringBuilder huNote = new StringBuilder();
        huNote.append(r.ganKai ? "杠开" : (r.selfDraw ? "自摸" : (r.qiangGang ? "抢杠" : "胡")));
        if (!fans.isEmpty()) {
            huNote.append('(');
            for (int i = 0; i < fans.size(); i++) {
                if (i > 0) {
                    huNote.append('、');
                }
                huNote.append(fans.get(i).display);
            }
            huNote.append(')');
        }
        notes.get(winner).add(huNote.toString());

        // ---- 杠钱（明1/补1/暗2；三种杠都三家各付，含庄翻倍；流局已排除） ----
        Map<Seat, int[]> gangCount = new EnumMap<Seat, int[]>(Seat.class); // {GANG,BU,AN}
        for (Seat g : Seat.values()) {
            List<Meld> ms = melds == null ? null : melds.get(g);
            if (ms == null) {
                continue;
            }
            boolean specialHandled = false;
            for (Meld m : ms) {
                if (m.type != Meld.Type.GANG && m.type != Meld.Type.BU_GANG && m.type != Meld.Type.AN_GANG) {
                    continue;
                }
                int unit = gangUnit(cfg, m.type);
                boolean special = false;
                if (dealerFirstGangSeat != null && g == dealerFirstGangSeat
                        && m.type == Meld.Type.GANG && !specialHandled) {
                    special = true; // 包杠：庄首张被明杠 → 庄代付该杠三家份
                    specialHandled = true;
                }
                // 三家各一份
                int sum = 0;
                for (Seat s : Seat.values()) {
                    if (s == g) {
                        continue;
                    }
                    int factor = (s == dealer || g == dealer) ? bottom : cfg.basePoint;
                    sum += factor * unit;
                }
                if (special) {
                    delta.put(dealer, delta.get(dealer) - sum);
                    delta.put(g, delta.get(g) + sum);
                    notes.get(g).add("包杠");
                } else {
                    for (Seat s : Seat.values()) {
                        if (s == g) {
                            continue;
                        }
                        int factor = (s == dealer || g == dealer) ? bottom : cfg.basePoint;
                        delta.put(s, delta.get(s) - factor * unit);
                        delta.put(g, delta.get(g) + factor * unit);
                    }
                }
                int[] c = gangCount.get(g);
                if (c == null) {
                    c = new int[3];
                    gangCount.put(g, c);
                }
                if (m.type == Meld.Type.GANG) {
                    c[0]++;
                } else if (m.type == Meld.Type.BU_GANG) {
                    c[1]++;
                } else {
                    c[2]++;
                }
            }
        }
        for (Seat g : Seat.values()) {
            int[] c = gangCount.get(g);
            if (c == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (c[0] > 0) {
                sb.append("明杠×").append(c[0]);
            }
            if (c[1] > 0) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append("补杠×").append(c[1]);
            }
            if (c[2] > 0) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append("暗杠×").append(c[2]);
            }
            if (sb.length() > 0) {
                notes.get(g).add(sb.toString());
            }
        }

        // ---- 花分（真/假/真对/假对，各自累加，三家各付且含庄翻倍） ----
        for (Seat s : Seat.values()) {
            List<Integer> fl = flowers == null ? null : flowers.get(s);
            if (fl == null || fl.isEmpty()) {
                continue;
            }
            List<String> tags = new ArrayList<String>();
            boolean seasons = hasAll(fl, 34, 35, 36, 37);
            boolean nobles = hasAll(fl, 38, 39, 40, 41);
            boolean hasTrue = seasons || nobles;
            if (seasons) {
                tags.add("真花·四季");
                collectFlower(s, cfg.flowerTrue, dealer, bottom, cfg, delta);
            }
            if (nobles) {
                tags.add("真花·四君子");
                collectFlower(s, cfg.flowerTrue, dealer, bottom, cfg, delta);
            }
            // 假花：数字 1~4 各有（大小写皆可）；有真花则归零
            if (!hasTrue) {
                boolean fake = true;
                for (int n = 1; n <= 4; n++) {
                    if (!hasAny(fl, 33 + n, 37 + n)) {
                        fake = false;
                        break;
                    }
                }
                if (fake && cfg.flowerFake > 0) {
                    tags.add("假花");
                    collectFlower(s, cfg.flowerFake, dealer, bottom, cfg, delta);
                }
            }
            // 真对花：本人两个位置花都抓到（必为同号一对）
            int off = s.stepsAfter(dealer);
            if (hasAll(fl, 34 + off, 38 + off) && cfg.pairTrueFlower > 0) {
                tags.add("真对花");
                collectFlower(s, cfg.pairTrueFlower, dealer, bottom, cfg, delta);
            }
            // 假对花：其它号的同号一对
            for (int n = 1; n <= 4; n++) {
                if (n == off + 1) {
                    continue;
                }
                if (hasAll(fl, 33 + n, 37 + n) && cfg.pairFakeFlower > 0) {
                    tags.add("假对花·" + n);
                    collectFlower(s, cfg.pairFakeFlower, dealer, bottom, cfg, delta);
                }
            }
            if (!tags.isEmpty()) {
                notes.get(s).add(String.join("、", tags));
            }
        }
        return new Settlement(delta, notes);
    }

    /** 花分：花家从另三家各收一份（庄相关笔=当前庄底分；纯闲↔闲=房间初始底分）。 */
    private static void collectFlower(Seat me, int unit, Seat dealer, int bottom, HainanConfig cfg,
                                      Map<Seat, Integer> delta) {
        if (unit <= 0) {
            return;
        }
        for (Seat s : Seat.values()) {
            if (s == me) {
                continue;
            }
            int factor = (s == dealer || me == dealer) ? bottom : cfg.basePoint;
            delta.put(s, delta.get(s) - factor * unit);
            delta.put(me, delta.get(me) + factor * unit);
        }
    }

    private static boolean hasAll(List<Integer> fl, int a, int b, int c, int d) {
        return fl.contains(a) && fl.contains(b) && fl.contains(c) && fl.contains(d);
    }

    private static boolean hasAll(List<Integer> fl, int a, int b) {
        return fl.contains(a) && fl.contains(b);
    }

    private static boolean hasAny(List<Integer> fl, int a, int b) {
        return fl.contains(a) || fl.contains(b);
    }

    /** winner 与 donor 之间单向吃碰杠次数 ≥ n 即成立（任一方向）。 */
    private static boolean relationAtLeast(Seat winner, Seat donor, Map<Seat, List<Meld>> melds, int n) {
        if (donor == null || melds == null) {
            return false;
        }
        int wd = 0, dw = 0;
        for (Seat owner : Seat.values()) {
            List<Meld> ms = melds.get(owner);
            if (ms == null) {
                continue;
            }
            for (Meld m : ms) {
                if (m.from == null) {
                    continue;
                }
                if (owner == winner && m.from == donor) {
                    wd++;
                } else if (owner == donor && m.from == winner) {
                    dw++;
                }
            }
        }
        return wd >= n || dw >= n;
    }

    /** 抢杠胡且该胡没有任何其它有番（杠者应独担三家）。 */
    private static boolean qiangNoOtherFan(RoundResult r) {
        RuleEnv env = envOf(r);
        RuleEnv e2 = new RuleEnv(env.concealed, env.melds, env.flowers, env.seatOffset, env.roundWindIdx, false, true);
        HainanFan.Gate g = HainanFan.gate(e2);
        return !(g.menQing || g.zhongFaBai || g.roundWindK || g.seatWindK || g.fourChow
                || g.posFlower || g.special || g.gangFan || g.eye258);
    }
}
