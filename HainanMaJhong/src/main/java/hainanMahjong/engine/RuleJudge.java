package hainanMahjong.engine;

import hainanMahjong.rules.HuLib;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.rule.RuleSet;

/**
 * 胡牌判定的唯一入口。
 *
 * <p>从 {@code RoomManager} 抽出，让"动作检测"和"抢杠/点炮流程"<b>共用同一份判定</b>，
 * 而不是各自内联一段 —— 判胡是最不能出现两套实现的地方。</p>
 *
 * <p>无状态、不加锁；只读 {@link Player} 与 {@link RuleSet}。</p>
 */
public class RuleJudge {

    private final RuleSet rules;

    public RuleJudge(RuleSet rules) {
        this.rules = rules;
    }

    /**
     * 判胡只看"暗牌"：吃/碰/杠已固定成组，不再折回 14 张一起重拆。
     * 暗牌（含摸/吃到的那张）张数 = 14 - 3×副露数，即 14/11/8/5/2，
     * 交给按张数查表的 {@code canHuConcealed}。
     *
     * <p>{@code extraTile < 0} 视为自摸（自摸本身即有番）。</p>
     */
    public boolean canHu(Player p, int extraTile) {
        return canHuWin(p, extraTile, extraTile < 0, false);
    }

    /** 能否胡这一张：{@code selfDraw} 自摸 / {@code qiangGang} 抢杠。 */
    public boolean canHuWin(Player p, int extraTile, boolean selfDraw, boolean qiangGang) {
        int[] concealed = buildConcealedHand(p, extraTile);
        // 标准规则只看结构合法性；海南还要求有番（受 fanGate 控制）
        return rules.canHu(concealed, p, extraTile, selfDraw, qiangGang);
    }

    /** 暗牌计数数组（34 张牌型，已含 {@code extraTile}）。 */
    public int[] buildConcealedHand(Player p, int extraTile) {
        int[] cnt = new int[HuLib.TOTAL_TILES];
        for (int t : p.hand) {
            cnt[t]++;
        }
        if (extraTile >= 0) {
            cnt[extraTile]++;
        }
        return cnt;
    }

    /**
     * 判胡结果/番型时把副露折回 14 张的虚拟手牌（牌面内容不变，仅用于取番型），
     * 结构合法性已由 {@link #canHu} 在暗牌上保证。
     */
    public int[] buildVirtualHand(Player p, int extraTile) {
        int[] cnt = new int[HuLib.TOTAL_TILES];
        for (Meld m : p.melds) {
            if (m.type() == Meld.Type.CHI) {
                cnt[m.tiles()[0]]++;
                cnt[m.tiles()[1]]++;
                cnt[m.tiles()[2]]++;
            } else {
                cnt[m.tiles()[0]] += 3; // 碰/杠都按一组 3 张算
            }
        }
        for (int t : p.hand) {
            cnt[t]++;
        }
        if (extraTile >= 0) {
            cnt[extraTile]++;
        }
        return cnt;
    }
}
