package hainanjong;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 海南麻将胡牌判定（查表法，不含鬼牌，含花牌）。
 *
 * <p>牌张表示（共 42 张）：</p>
 * <ul>
 *   <li>0..8   万（1万 ~ 9万）</li>
 *   <li>9..17  筒（1筒 ~ 9筒）</li>
 *   <li>18..26 条（1条 ~ 9条）</li>
 *   <li>27..33 字（东 南 西 北 中 发 白）</li>
 *   <li>34..41 花牌（春 夏 秋 冬 梅 兰 竹 菊），只计番，不参与成牌</li>
 * </ul>
 *
 * <p>查表法思路：不含鬼牌时，某一花色（9 张）或字牌（7 张）能否拆成
 * “若干副（刻子/顺子）＋可选一个将”是固定的。离线把所有这些牌型的张数分布
 * 编码成一个十进制整数（每一位是一张牌的张数）存入 HashSet，运行时对每种
 * 花色做 O(1) 查表，再做跨花色的“将位”分配即可。</p>
 */
public class HuLib {

    public static final int TILE_TYPES = 34;   // 万筒条字
    public static final int HUA = 34;          // 花牌起始下标
    public static final int TOTAL_TILES = 42;  // 34 + 8 花牌

    private static final String[] ZI_NAMES = {"东", "南", "西", "北", "中", "发", "白"};
    private static final String[] HUA_NAMES = {"春", "夏", "秋", "冬", "梅", "兰", "竹", "菊"};

    // 查表：万/筒/条（9 张，可吃）与字牌（7 张，只能碰）
    @SuppressWarnings("unchecked")
    private static final Set<Integer>[] SUIT_NO_EYE = new HashSet[3];
    @SuppressWarnings("unchecked")
    private static final Set<Integer>[] SUIT_EYE = new HashSet[3];
    private static final Set<Integer> HONOR_NO_EYE = new HashSet<Integer>();
    private static final Set<Integer> HONOR_EYE = new HashSet<Integer>();

    static {
        for (int c = 0; c < 3; c++) {
            SUIT_NO_EYE[c] = genNoEye(9, true);
            SUIT_EYE[c] = genEye(9, true);
        }
        HONOR_NO_EYE.addAll(genNoEye(7, false));
        HONOR_EYE.addAll(genEye(7, false));
    }

    // ==================== 对外接口 ====================

    /** 传入整副手牌（已含胡的那张，共 14 张），返回判定结果。 */
    public static HuResult checkHu(int[] tiles) {
        int[] t = new int[TOTAL_TILES];
        System.arraycopy(tiles, 0, t, 0, Math.min(tiles.length, TOTAL_TILES));

        int flowerCount = sum(t, HUA, TOTAL_TILES - 1);
        int total = sum(t, 0, TILE_TYPES - 1);

        // 任何牌型都需要恰好 14 张（花牌不占张数）
        if (total != 14) {
            return HuResult.notHu(flowerCount);
        }

        // 十三幺：独立牌型，优先级最高
        if (isThirteenOrphans(t)) {
            return HuResult.hu(flowerCount, FanType.SHI_SAN_YAO);
        }

        // 七对
        if (isSevenPairs(t)) {
            List<FanType> fans = new ArrayList<FanType>();
            fans.add(FanType.QI_DUI);
            addSuitFan(t, fans);
            return HuResult.hu(flowerCount, fans);
        }

        // 普通胡（查表法）
        if (isStandard(t)) {
            List<FanType> fans = new ArrayList<FanType>();
            if (isPengPengHu(t)) {
                fans.add(FanType.PENG_PENG_HU);
            }
            addSuitFan(t, fans);
            return HuResult.hu(flowerCount, fans);
        }

        return HuResult.notHu(flowerCount);
    }

    /** 传入 13 张手牌 + 胡的那张牌 {@code curCard}（下标 < 42 时计入）。 */
    public static HuResult checkHu(int[] handCards, int curCard) {
        int[] t = new int[TOTAL_TILES];
        System.arraycopy(handCards, 0, t, 0, Math.min(handCards.length, TOTAL_TILES));
        if (curCard >= 0 && curCard < TOTAL_TILES) {
            t[curCard]++;
        }
        return checkHu(t);
    }

    // ==================== 普通胡（查表法核心） ====================

    private static boolean isStandard(int[] t) {
        int eyeCount = 0;

        // 万、筒、条
        for (int c = 0; c < 3; c++) {
            int from = c * 9, to = from + 8;
            int cnt = sum(t, from, to);
            if (cnt == 0) {
                continue;
            }
            int key = encode(t, from, to);
            if (cnt % 3 == 2) {
                eyeCount++;
                if (!SUIT_EYE[c].contains(key)) {
                    return false;
                }
            } else if (cnt % 3 == 0) {
                if (!SUIT_NO_EYE[c].contains(key)) {
                    return false;
                }
            } else {
                return false; // 余 1 张，无法成整副
            }
        }

        // 字牌
        int honorCnt = sum(t, 27, 33);
        if (honorCnt > 0) {
            int key = encode(t, 27, 33);
            if (honorCnt % 3 == 2) {
                eyeCount++;
                if (!HONOR_EYE.contains(key)) {
                    return false;
                }
            } else if (honorCnt % 3 == 0) {
                if (!HONOR_NO_EYE.contains(key)) {
                    return false;
                }
            } else {
                return false;
            }
        }

        return eyeCount == 1;
    }

    // ==================== 番型判定 ====================

    private static final int[] ORPHANS = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};

    private static boolean isThirteenOrphans(int[] t) {
        for (int i = 0; i < TILE_TYPES; i++) {
            if (t[i] > 0 && !isOrphan(i)) {
                return false;
            }
        }
        for (int o : ORPHANS) {
            if (t[o] == 0) {
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

    private static boolean isSevenPairs(int[] t) {
        for (int i = 0; i < TILE_TYPES; i++) {
            if (t[i] % 2 != 0) {
                return false;
            }
        }
        return true; // 张数已保证为 14
    }

    private static boolean isPengPengHu(int[] t) {
        int pair = 0, pung = 0;
        for (int i = 0; i < TILE_TYPES; i++) {
            int c = t[i];
            if (c == 0) {
                continue;
            }
            if (c == 2) {
                pair++;
            } else if (c == 3) {
                pung++;
            } else {
                return false; // 出现 1 或 4 张，必含顺子或非法
            }
        }
        return pair == 1 && pung == 4;
    }

    private static boolean isQingYiSe(int[] t) {
        int suitCount = 0;
        for (int c = 0; c < 3; c++) {
            if (sum(t, c * 9, c * 9 + 8) > 0) {
                suitCount++;
            }
        }
        int honorCount = sum(t, 27, 33);
        return suitCount == 1 && honorCount == 0;
    }

    private static boolean isHunYiSe(int[] t) {
        int suitCount = 0;
        for (int c = 0; c < 3; c++) {
            if (sum(t, c * 9, c * 9 + 8) > 0) {
                suitCount++;
            }
        }
        int honorCount = sum(t, 27, 33);
        return suitCount == 1 && honorCount > 0;
    }

    private static void addSuitFan(int[] t, List<FanType> fans) {
        if (isQingYiSe(t)) {
            fans.add(FanType.QING_YI_SE);
        } else if (isHunYiSe(t)) {
            fans.add(FanType.HUN_YI_SE);
        }
    }

    // ==================== 查表生成 ====================

    private static Set<Integer> genNoEye(int size, boolean allowChow) {
        Set<Integer> set = new HashSet<Integer>();
        genMelds(new int[size], size, allowChow, 0, set);
        return set;
    }

    private static Set<Integer> genEye(int size, boolean allowChow) {
        Set<Integer> set = new HashSet<Integer>();
        int[] t = new int[size];
        for (int i = 0; i < size; i++) {
            t[i] = 2;
            genMelds(t, size, allowChow, 0, set);
            t[i] = 0;
        }
        return set;
    }

    /** 递归枚举 0..4 副（刻子/顺子），把每个中间状态编码后入表。 */
    private static void genMelds(int[] t, int size, boolean allowChow, int meldCount, Set<Integer> set) {
        set.add(encode(t, 0, size - 1));
        if (meldCount >= 4) {
            return;
        }
        // 刻子
        for (int i = 0; i < size; i++) {
            if (t[i] + 3 <= 4) {
                t[i] += 3;
                genMelds(t, size, allowChow, meldCount + 1, set);
                t[i] -= 3;
            }
        }
        // 顺子
        if (allowChow) {
            for (int i = 0; i < size - 2; i++) {
                if (t[i] + 1 <= 4 && t[i + 1] + 1 <= 4 && t[i + 2] + 1 <= 4) {
                    t[i]++;
                    t[i + 1]++;
                    t[i + 2]++;
                    genMelds(t, size, allowChow, meldCount + 1, set);
                    t[i]--;
                    t[i + 1]--;
                    t[i + 2]--;
                }
            }
        }
    }

    private static int encode(int[] t, int from, int to) {
        int key = 0;
        for (int i = from; i <= to; i++) {
            key = key * 10 + t[i];
        }
        return key;
    }

    private static int sum(int[] t, int from, int to) {
        int s = 0;
        for (int i = from; i <= to; i++) {
            s += t[i];
        }
        return s;
    }

    // ==================== 展示辅助 ====================

    public static String cardName(int i) {
        if (i < 9) {
            return (i + 1) + "万";
        }
        if (i < 18) {
            return (i - 8) + "筒";
        }
        if (i < 27) {
            return (i - 17) + "条";
        }
        if (i < 34) {
            return ZI_NAMES[i - 27];
        }
        return HUA_NAMES[i - 34];
    }

    public static String formatHand(int[] tiles) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(tiles.length, TOTAL_TILES);
        for (int i = 0; i < n; i++) {
            if (tiles[i] > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(cardName(i)).append('x').append(tiles[i]);
            }
        }
        return sb.toString();
    }

    /** 表规模（调试/观察用）。 */
    public static int[] tableSizes() {
        int s = 0, e = 0;
        for (int c = 0; c < 3; c++) {
            s += SUIT_NO_EYE[c].size();
            e += SUIT_EYE[c].size();
        }
        return new int[]{s, e, HONOR_NO_EYE.size(), HONOR_EYE.size()};
    }
}
