package hainanMahjong.engine;

import hainanMahjong.engine.model.Action;
import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.rule.RuleSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作检测：算出"此刻这个玩家能做什么"。
 *
 * <p>从 {@code RoomManager} 抽出。<b>纯读</b>——只读 {@link Player}、{@link GameConfig}、
 * {@link RuleSet}，<b>不修改任何状态、不发消息、不询问玩家</b>。
 * 因此它可以单独测试（给一个手牌，断言能出哪些动作）。</p>
 *
 * <p>检测出来的是"<b>可选集</b>"；要不要真的执行由 {@code RoomManager} 决定。</p>
 */
class ActionDetector {

    private final RuleSet rules;
    private final GameConfig config;
    private final RuleJudge huJudge;

    ActionDetector(RuleSet rules, GameConfig config, RuleJudge huJudge) {
        this.rules = rules;
        this.config = config;
        this.huJudge = huJudge;
    }

    /** 别人打出 {@code tile} 后，玩家 {@code q} 可做的动作（已按优先级排序）。 */
    List<Action> onDiscard(Player q, int tile, Seat discarder) {
        List<Action> acts = new ArrayList<>();

        // 已报听：不可换牌，只能胡（不能再吃碰明杠）
        if (rules.locksHandWhenReported(q.seat)) {
            if (huJudge.canHu(q, tile)) {
                acts.add(Action.hu(tile, discarder));
            }
            TurnUtils.sortByPriority(acts);
            return acts;
        }

        if (huJudge.canHu(q, tile)) {
            acts.add(Action.hu(tile, discarder));
        }
        if (q.count(tile) >= 2) {
            acts.add(Action.peng(tile, discarder));
        }
        if (q.count(tile) >= 3) {
            acts.add(Action.gang(tile, discarder));
        }
        boolean chiAllowed = !config.chiOnlyNext || q.seat == discarder.next();
        if (chiAllowed && TurnUtils.isSuit(tile)) {
            for (int[] seq : TurnUtils.chiSequences(tile)) {
                boolean ok = true;
                for (int x : seq) {
                    if (x != tile && q.count(x) < 1) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    // 海南吃后禁打：吃完若无可出的牌（全禁）则本次不提供这种吃法
                    if (!rules.canChiDiscard(q, seq, tile)) {
                        continue;
                    }
                    acts.add(Action.chi(seq, tile, discarder));
                }
            }
        }
        TurnUtils.sortByPriority(acts);
        return acts;
    }

    /** 摸牌后玩家可做的动作：自摸胡 / 暗杠 / 补杠。 */
    List<Action> onDraw(Player p) {
        List<Action> acts = new ArrayList<>();

        if (huJudge.canHu(p, -1)) {
            acts.add(Action.huSelfDraw(p.lastDrawn));
        }
        boolean reported = rules.locksHandWhenReported(p.seat);
        for (int t = 0; t < 34; t++) {
            if (p.count(t) == 4) {
                acts.add(Action.anGang(t));
            }
            if (!reported && p.count(t) >= 1 && hasPengOf(p, t)) {
                acts.add(Action.buGang(t));
            }
        }
        TurnUtils.sortByPriority(acts);
        return acts;
    }

    private boolean hasPengOf(Player p, int tile) {
        for (Meld m : p.melds) {
            if (m.type() == Meld.Type.PENG && m.tiles()[0] == tile) {
                return true;
            }
        }
        return false;
    }
}
