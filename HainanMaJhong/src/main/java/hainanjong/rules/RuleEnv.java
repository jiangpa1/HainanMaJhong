package hainanjong.rules;

import hainanjong.game.Meld;
import hainanjong.game.Seat;

import java.util.Collections;
import java.util.List;

/**
 * 一次“成胡候选”的规则上下文：一局中的座位风、令风、位置花、副露、
 * 已收花牌与胡的方式。供有番门禁与番型判定使用。
 *
 * <p>牌号约定（与 HuLib 一致）：0..8万 9..17筒 18..26条 27..33字(东南西北中发白)，
 * 34..41 花（春 夏 秋 冬 梅 兰 竹 菊）。</p>
 */
public class RuleEnv {

    /** 暗牌张数 [0..33]（已含胡/抢杠拿到的那张；花牌不在此数组）。 */
    public final int[] concealed;

    /** 吃/碰/杠（杠按 4 张）。 */
    public final List<Meld> melds;

    /** 本局已收集到的花牌下标（34..41）。 */
    public final List<Integer> flowers;

    /** 相对庄家的顺位 0..3：0=庄(东) 1=下家(南) 2=对家(西) 3=上家(北)。由 seat.stepsAfter(dealer) 得出。 */
    public final int seatOffset;

    /** 当前“令”风 0..3：0=东 1=南 2=西 3=北。 */
    public final int roundWindIdx;

    /** 是否自摸（含杠上开花自摸）。 */
    public final boolean selfDraw;

    /** 是否抢杠胡（杠家那张是补杠，被你胡）。 */
    public final boolean qiangGang;

    public RuleEnv(int[] concealed, List<Meld> melds, List<Integer> flowers,
                   int seatOffset, int roundWindIdx, boolean selfDraw, boolean qiangGang) {
        this.concealed = concealed;
        this.melds = melds;
        this.flowers = flowers == null ? Collections.<Integer>emptyList() : flowers;
        this.seatOffset = seatOffset & 3;
        this.roundWindIdx = roundWindIdx & 3;
        this.selfDraw = selfDraw;
        this.qiangGang = qiangGang;
    }

    public int seatWindTile() {
        return 27 + seatOffset;          // 座位风：庄=东(27)+0 依次
    }

    public int roundWindTile() {
        return 27 + roundWindIdx;        // 令风
    }

    /** 本座位两个位置花：四季(34+o) 与 四君子(38+o)。 */
    public int positionFlowerA() {
        return 34 + seatOffset;
    }

    public int positionFlowerB() {
        return 38 + seatOffset;
    }

    public boolean caughtPositionFlower() {
        int a = positionFlowerA(), b = positionFlowerB();
        for (int f : flowers) {
            if (f == a || f == b) {
                return true;
            }
        }
        return false;
    }

    /** 由座位与庄算出顺位（东→…）。 */
    public static int offsetOf(Seat seat, Seat dealer) {
        return seat.stepsAfter(dealer);
    }

    /** 暗牌 + 副露折成“全手牌张数数组[34]”（不含花；碰/杠按一组 3 张折算，杠不按 4）。 */
    public int[] fullCounts() {
        int[] full = new int[34];
        if (concealed != null) {
            int n = Math.min(concealed.length, 34);
            System.arraycopy(concealed, 0, full, 0, n);
        }
        for (Meld m : melds) {
            if (m.type == Meld.Type.CHI) {
                for (int t : m.tiles) {
                    if (t >= 0 && t < 34) {
                        full[t]++;
                    }
                }
            } else {
                int t = m.tiles == null || m.tiles.length == 0 ? -1 : m.tiles[0];
                if (t >= 0 && t < 34) {
                    full[t] += 3; // 碰/明杠/暗杠/补杠 均按一组 3 张
                }
            }
        }
        return full;
    }

    /** 是否做过杠（明/暗/补）。 */
    public boolean didGang() {
        for (Meld m : melds) {
            Meld.Type ty = m.type;
            if (ty == Meld.Type.GANG || ty == Meld.Type.AN_GANG || ty == Meld.Type.BU_GANG) {
                return true;
            }
        }
        return false;
    }

    /** 门清：无任何吃/碰/明杠/补杠（暗杠不算破门清）。 */
    public boolean isMenQing() {
        for (Meld m : melds) {
            if (m.type != Meld.Type.AN_GANG) {
                return false;
            }
        }
        return true;
    }
}
