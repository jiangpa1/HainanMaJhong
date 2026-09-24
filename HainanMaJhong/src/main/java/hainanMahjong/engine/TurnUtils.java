package hainanMahjong.engine;

import hainanMahjong.rules.HuLib;
import hainanMahjong.engine.model.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 回合流程里的纯计算工具：牌张判定、吃法枚举、动作排序。
 *
 * <p>从 {@code RoomManager} 抽出 —— 这几个方法<b>无状态、不读配置、不改玩家</b>，
 * 只是纯粹的算术与排序，放这里便于单独理解和测试。</p>
 */
final class TurnUtils {

    private TurnUtils() {
        // 工具类，不实例化
    }

    /** 是否花牌（索引 >= {@link HuLib#HUA}）。 */
    static boolean isFlower(int t) {
        return t >= HuLib.HUA;
    }

    /** 是否数牌（万/筒/条，索引 0..26）。字牌与花牌都不是。 */
    static boolean isSuit(int t) {
        return t >= 0 && t < 27;
    }

    /**
     * 用 {@code tile} 能组成的吃法（最多 3 种：左吃 / 中吃 / 右吃）。
     *
     * <p>只做"序列上存在"的判断，<b>不检查玩家手里有没有那两张</b> —— 那个由调用方做。</p>
     */
    static List<int[]> chiSequences(int tile) {
        List<int[]> res = new ArrayList<>();
        if (!isSuit(tile)) return res;
        int p = tile % 9;
        if (p - 2 >= 0) res.add(new int[]{tile - 2, tile - 1, tile});
        if (p - 1 >= 0 && p + 1 <= 8) res.add(new int[]{tile - 1, tile, tile + 1});
        if (p + 2 <= 8) res.add(new int[]{tile, tile + 1, tile + 2});
        return res;
    }

    /** 按优先级升序排列（`胡 > 杠 > 碰 > 吃`，由 {@link Action#priority()} 给出）。 */
    static void sortByPriority(List<Action> acts) {
        Collections.sort(acts, new Comparator<Action>() {
            public int compare(Action a, Action b) {
                return a.priority() - b.priority();
            }
        });
    }

    /** 一组动作里的最高优先级（数值最小）；空集合返回 {@link Integer#MAX_VALUE}。 */
    static int bestPriority(List<Action> acts) {
        int best = Integer.MAX_VALUE;
        for (Action a : acts) {
            if (a.priority() < best) {
                best = a.priority();
            }
        }
        return best;
    }
}
