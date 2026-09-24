package hainanMahjong.engine.rule;

import hainanMahjong.rules.HuLib;
import hainanMahjong.engine.model.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 海南规则：<b>吃后禁打</b>。
 *
 * <p>用 {@code eaten} 吃成顺子后，自己手里"能补成顺子"的那几张牌，本次出牌不能打出去
 * （否则等于用吃牌做牌后立刻反打，破坏吃牌的代价）。</p>
 *
 * <p>本类<b>无状态、不加锁</b>，是纯函数集合 —— 只读 {@link Player#hand}，
 * 所以可以独立单元测试（不依赖引擎、不依赖控制器）。</p>
 *
 * <p>从 {@code RoomManager} 抽出的三个方法，逻辑逐字保留。</p>
 */
public final class ChiBan {

    private ChiBan() {
        // 工具类，不实例化
    }

    /** 用 seq(含被吃牌 eaten) 吃时，自己两张手牌可"补成顺子"的牌值集合。例：5、6吃7 → {4,7}；5、7吃6 → {6}。 */
    public static List<Integer> banValues(int[] seq, int eaten) {
        int a = -1, b = -1;
        for (int t : seq) {
            if (t == eaten) {
                continue;
            }
            if (a < 0) {
                a = t;
            } else {
                b = t;
            }
        }
        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }
        List<Integer> ban = new ArrayList<>();
        if (b == a + 1) {
            // 两个补张只有“与 a,b 同花色且序号 1..9 内”才存在：a-1 在 a 为该花第 1 张时越界(如 1筒-1=9万)；
            // b+1 在 b 为该花第 9 张时越界。索引跨花色时不能当作可补顺子的牌。
            if (a % 9 > 0) {
                ban.add(a - 1); // 顺子低端补张
            }
            if (b % 9 < 8) {
                ban.add(b + 1); // 顺子高端补张
            }
        } else if (b == a + 2) {
            ban.add(a + 1); // 嵌张的中间那张（与 a,b 同花色、必然有效）
        }
        return ban;
    }

    /** 该吃法吃完(去掉自己两张)后手里是否还有可出的牌；没有(全禁)则本次不可吃。 */
    public static boolean canDiscard(Player p, int[] seq, int eaten) {
        int[] c = new int[HuLib.TOTAL_TILES];
        for (int t : p.hand) {
            c[t]++;
        }
        for (int t : seq) {
            if (t != eaten) {
                c[t]--;
            }
        }
        List<Integer> ban = banValues(seq, eaten);
        for (int t = 0; t < HuLib.TOTAL_TILES; t++) {
            if (c[t] > 0 && !ban.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 吃完(自己两张已移除)后手里仍然存在的禁打牌（本次出牌限制）。 */
    public static List<Integer> present(Player p, int[] seq, int eaten) {
        List<Integer> out = new ArrayList<>();
        for (int t : banValues(seq, eaten)) {
            if (p.count(t) > 0) {
                out.add(t);
            }
        }
        return out;
    }
}
