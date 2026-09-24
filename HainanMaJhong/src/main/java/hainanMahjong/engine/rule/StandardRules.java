package hainanMahjong.engine.rule;

import hainanMahjong.rules.HuLib;
import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.vo.recordvo.RulesCallbacks;

import java.util.List;

/**
 * 标准规则（{@code GameConfig.hainan == false}）。
 *
 * <p>逐条对应原 {@code RoomManager} 里"非海南"分支的行为：</p>
 * <ul>
 *   <li>无"底牌 15 张荒庄" —— 牌墙真空了由 {@code takeFromWall()} 返回 -1 收束</li>
 *   <li>胡牌只看结构合法性（{@link HuLib#canHuConcealed}），不看番</li>
 *   <li>无抢杠、无吃后禁打、无天胡、报听不锁手</li>
 * </ul>
 */
final class StandardRules implements RuleSet {

    private final RulesCallbacks cb;

    /** 保留配置引用：标准规则当前用不到，但接口签名统一，便于以后加标准规则专属项。 */
    @SuppressWarnings("unused")
    private final GameConfig config;

    StandardRules(RulesCallbacks cb, GameConfig config) {
        this.cb = cb;
        this.config = config;
    }

    @Override
    public boolean shouldEndDraw(int deckSize) {
        return false;
    }

    @Override
    public boolean canHu(int[] concealed, Player p, int extraTile, boolean selfDraw, boolean qiangGang) {
        return cb.canHu(concealed);
    }

    @Override
    public boolean isTianHu(boolean selfDraw, int totalDiscards) {
        return false;
    }

    @Override
    public boolean canQiangGang(Player ganger, int tile) {
        return false;
    }

    @Override
    public boolean canChiDiscard(Player p, int[] seq, int eaten) {
        return true;
    }

    @Override
    public List<Integer> chiBanAfterEat(Player p, int[] seq, int eaten) {
        return null;
    }

    @Override
    public boolean locksHandWhenReported(Seat seat) {
        return false;
    }
}
