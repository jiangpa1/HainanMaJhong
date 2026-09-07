package hainanjong.rules;

import hainanjong.FanType;
import hainanjong.HuLib;
import hainanjong.game.Meld;

import java.util.ArrayList;
import java.util.List;

/**
 * 海南规则判定：胡牌合法性 + 有番门禁（满足任一有番条件才能胡）+
 * 番型倍数列表（命中多个相加；无特殊番型时兜底“平胡”）。
 *
 * <p>“有番条件”来自规则第二章（门清/中发白刻/令风刻/座位风刻/四顺子带对/
 * 位置花/特殊牌型/自摸杠开/抢杠/杠/258将）。这些只决定能不能胡（门禁），
 * 其中命中的“番型”（碰碰/清混/七对/龙七对/十三幺）才计入结算倍数。</p>
 */
public final class HainanFan {

    private HainanFan() {
    }

    // ==================== 对外 ====================

    /** 结构合法：暗牌张数 2/5/8/11/14 且能成胡。 */
    public static boolean canHuConcealed(RuleEnv e) {
        return HuLib.canHuConcealed(e.concealed);
    }

    /** 结构合法 且 满足有番门禁（否则不能胡）。 */
    public static boolean legalAndHasFan(RuleEnv e) {
        return canHuConcealed(e) && gate(e).any();
    }

    /** 结算番型列表：空则兜底平胡。命中多个（如 清一色+碰碰胡）都列入，倍数相加。 */
    public static List<FanType> multiplierFans(RuleEnv e) {
        int[] full = e.fullCounts();
        List<FanType> fans = new ArrayList<FanType>();
        if (isThirteenOrphans(full)) {
            fans.add(FanType.SHI_SAN_YAO);
        } else if (isSevenPairs(full)) {
            if (hasQuad(full)) {
                fans.add(FanType.LONG_QI_DUI);
            } else {
                fans.add(FanType.QI_DUI);
            }
            addColor(fans, full);
        } else {
            if (isPengPeng(full)) {
                fans.add(FanType.PENG_PENG_HU);
            }
            addColor(fans, full);
        }
        if (fans.isEmpty()) {
            fans.add(FanType.PING_HU);
        }
        return fans;
    }

    /** 倍数总和（config 取各番型的可设倍数；平胡只会在无其它番型时出现）。 */
    public static int multiplierSum(RuleEnv e, HainanConfig cfg) {
        int s = 0;
        for (FanType f : multiplierFans(e)) {
            s += cfg.multiplier(f);
        }
        return s;
    }

    // ==================== 有番门禁 ====================

    /** 各“有番条件”命中情况。 */
    public static final class Gate {
        public final boolean menQing;        // 1 门清
        public final boolean zhongFaBai;     // 2 中发白刻子
        public final boolean roundWindK;     // 3 令风刻子
        public final boolean seatWindK;      // 4 座位风刻子
        public final boolean fourChow;       // 5 4 顺子带一对(将非大字)
        public final boolean posFlower;      // 6 抓到位置花
        public final boolean special;        // 7 特殊牌型(碰碰/十三幺/混一色/清一色/七对/龙七对)
        public final boolean selfDrawFan;    // 8 自摸/杠开
        public final boolean qiangGangFan;   // 9 抢杠胡
        public final boolean gangFan;        // 10 杠(明/补/暗)
        public final boolean eye258;         // 11 2/5/8 做将

        Gate(boolean menQing, boolean zhongFaBai, boolean roundWindK, boolean seatWindK,
             boolean fourChow, boolean posFlower, boolean special, boolean selfDrawFan,
             boolean qiangGangFan, boolean gangFan, boolean eye258) {
            this.menQing = menQing;
            this.zhongFaBai = zhongFaBai;
            this.roundWindK = roundWindK;
            this.seatWindK = seatWindK;
            this.fourChow = fourChow;
            this.posFlower = posFlower;
            this.special = special;
            this.selfDrawFan = selfDrawFan;
            this.qiangGangFan = qiangGangFan;
            this.gangFan = gangFan;
            this.eye258 = eye258;
        }

        public boolean any() {
            return menQing || zhongFaBai || roundWindK || seatWindK || fourChow || posFlower
                    || special || selfDrawFan || qiangGangFan || gangFan || eye258;
        }

        public List<String> names() {
            List<String> s = new ArrayList<String>();
            if (menQing) s.add("门清");
            if (zhongFaBai) s.add("中发白刻");
            if (roundWindK) s.add("令风刻");
            if (seatWindK) s.add("座位风刻");
            if (fourChow) s.add("全顺子");
            if (posFlower) s.add("位置花");
            if (special) s.add("特殊牌型");
            if (selfDrawFan) s.add("自摸");
            if (qiangGangFan) s.add("抢杠");
            if (gangFan) s.add("杠");
            if (eye258) s.add("2/5/8将");
            return s;
        }
    }

    public static Gate gate(RuleEnv e) {
        int[] full = e.fullCounts();
        boolean zfb = count(full, 31) >= 3 || count(full, 32) >= 3 || count(full, 33) >= 3;
        boolean rw = full[e.roundWindTile()] >= 3;
        boolean sw = full[e.seatWindTile()] >= 3;
        boolean special = isSpecial(full);
        boolean posFlower = e.caughtPositionFlower();
        return new Gate(e.isMenQing(), zfb, rw, sw,
                isFourChowNonHonorPair(e), posFlower, special,
                e.selfDraw, e.qiangGang, e.didGang(), isEye258(e));
    }

    private static boolean isSpecial(int[] full) {
        if (isThirteenOrphans(full)) {
            return true;
        }
        if (isSevenPairs(full)) {
            return true;
        }
        if (isPengPeng(full)) {
            return true;
        }
        // 花色番（清/混）也算“有番”，但清/混可能和普通顺子手重叠，这里单独查
        int suits = suitCount(full), honor = honorCount(full);
        return suits == 1 && honor >= 0 && (honor > 0 || totalCount(full) == 14);
    }

    /** 有番 #5：无碰杠、全副露为吃、无字牌、暗牌可拆成顺子+非字将。 */
    private static boolean isFourChowNonHonorPair(RuleEnv e) {
        for (Meld m : e.melds) {
            if (m.type != Meld.Type.CHI) {
                return false;
            }
        }
        int[] full = e.fullCounts();
        if (honorCount(full) > 0) {
            return false;
        }
        int[] cnt = e.concealed;
        // 试将：将必须是数牌(0..26)，去掉后将余下全拆成顺子
        for (int i = 0; i < 27; i++) {
            if (cnt[i] >= 2) {
                cnt[i] -= 2;
                boolean ok = decompose(cnt, true);
                cnt[i] += 2;
                if (ok) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 有番 #11：存在将位为 2/5/8（万筒条），余下可拆成刻/顺。 */
    private static boolean isEye258(RuleEnv e) {
        int[] cnt = e.concealed;
        for (int i = 0; i < 27; i++) {
            if (cnt[i] >= 2 && is258(i)) {
                cnt[i] -= 2;
                boolean ok = decompose(cnt, false);
                cnt[i] += 2;
                if (ok) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean is258(int tile) {
        if (tile >= 27) {
            return false;
        }
        int m = tile % 9;
        return m == 1 || m == 4 || m == 7; // 2/5/8
    }

    // ==================== 形状检测（作用于折回 14 张的全手牌） ====================

    private static boolean isSevenPairs(int[] full) {
        if (totalCount(full) != 14) {
            return false;
        }
        for (int i = 0; i < 34; i++) {
            if (full[i] % 2 != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasQuad(int[] full) {
        for (int i = 0; i < 34; i++) {
            if (full[i] == 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean isThirteenOrphans(int[] full) {
        if (totalCount(full) != 14) {
            return false;
        }
        for (int i = 0; i < 34; i++) {
            if (full[i] > 0 && !isOrphan(i)) {
                return false;
            }
        }
        for (int o : ORPHANS) {
            if (full[o] == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOrphan(int i) {
        if (i >= 27) {
            return true;
        }
        int m = i % 9;
        return m == 0 || m == 8;
    }

    private static boolean isPengPeng(int[] full) {
        if (totalCount(full) != 14) {
            return false;
        }
        int pair = 0, pung = 0;
        for (int i = 0; i < 34; i++) {
            int c = full[i];
            if (c == 0) {
                continue;
            }
            if (c == 2) {
                pair++;
            } else if (c == 3) {
                pung++;
            } else {
                return false;
            }
        }
        return pair == 1 && pung == 4;
    }

    private static void addColor(List<FanType> fans, int[] full) {
        int suits = suitCount(full), honor = honorCount(full);
        if (honor == 0 && suits == 1) {
            fans.add(FanType.QING_YI_SE);
        } else if (honor > 0 && suits == 1) {
            fans.add(FanType.HUN_YI_SE);
        }
    }

    // ==================== 拆分工具 ====================

    /** 递归把整副牌拆成若干“整副”(刻/顺，或仅顺)。返回能否拆完。 */
    private static boolean decompose(int[] cnt, boolean onlyChow) {
        int first = -1;
        for (int i = 0; i < 34; i++) {
            if (cnt[i] > 0) {
                first = i;
                break;
            }
        }
        if (first == -1) {
            return true;
        }
        if (!onlyChow && cnt[first] >= 3) {
            cnt[first] -= 3;
            boolean r = decompose(cnt, onlyChow);
            cnt[first] += 3;
            if (r) {
                return true;
            }
        }
        if (first < 27 && first % 9 <= 6 && cnt[first + 1] > 0 && cnt[first + 2] > 0) {
            cnt[first]--;
            cnt[first + 1]--;
            cnt[first + 2]--;
            boolean r = decompose(cnt, onlyChow);
            cnt[first]++;
            cnt[first + 1]++;
            cnt[first + 2]++;
            if (r) {
                return true;
            }
        }
        return false;
    }

    private static final int[] ORPHANS = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};

    private static int count(int[] full, int i) {
        return i >= 0 && i < full.length ? full[i] : 0;
    }

    private static int totalCount(int[] full) {
        int s = 0;
        for (int i = 0; i < 34; i++) {
            s += full[i];
        }
        return s;
    }

    private static int suitCount(int[] full) {
        boolean[] has = new boolean[3];
        for (int i = 0; i < 27; i++) {
            if (full[i] > 0) {
                has[i / 9] = true;
            }
        }
        int n = 0;
        for (boolean b : has) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    private static int honorCount(int[] full) {
        int s = 0;
        for (int i = 27; i < 34; i++) {
            s += full[i];
        }
        return s;
    }
}
