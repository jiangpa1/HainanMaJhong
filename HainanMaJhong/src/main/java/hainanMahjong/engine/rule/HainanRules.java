package hainanMahjong.engine.rule;

import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.rules.HainanFan;
import hainanMahjong.rules.RuleEnv;
import hainanMahjong.vo.recordvo.RulesCallbacks;

import java.util.List;

/**
 * 海南规则（{@code GameConfig.hainan == true}）。
 *
 * <p>逐条对应原 {@code RoomManager} 里"海南"分支的行为：</p>
 * <ul>
 *   <li>牌墙剩 {@link #WALL_END 15} 张即荒庄</li>
 *   <li>胡牌要"有番"（受 {@code fanGate} 控制），并计入令风/门风/花牌</li>
 *   <li>天胡 = 开局首轮无人出过牌时的自摸</li>
 *   <li>抢杠：有人补杠时向其余三家询问</li>
 *   <li>吃后禁打：吃完若所有能打的牌都被禁，则这次吃不允许</li>
 *   <li>报听锁手：已报听则抓到什么打什么</li>
 * </ul>
 */
final class HainanRules implements RuleSet {

    private final RulesCallbacks cb;
    private final GameConfig config;

    HainanRules(RulesCallbacks cb, GameConfig config) {
        this.cb = cb;
        this.config = config;
    }

    /** 海南的"底牌"张数：牌墙剩这么多时荒庄。 */
    static final int WALL_END = 15;

    @Override
    public boolean shouldEndDraw(int deckSize) {
        return deckSize <= WALL_END;
    }

    @Override
    public boolean canHu(int[] concealed, Player p, int extraTile, boolean selfDraw, boolean qiangGang) {
        RuleEnv env = new RuleEnv(concealed, p.melds, p.flowers,
                p.seat.stepsAfter(cb.dealer()), cb.roundWindIdx(), selfDraw, qiangGang);
        // 有番门禁：开（默认）=结构合法且有番才能胡；关=无番，结构合法即可胡
        return config.fanGate ? HainanFan.legalAndHasFan(env) : HainanFan.canHuConcealed(env);
    }

    @Override
    public boolean isTianHu(boolean selfDraw, int totalDiscards) {
        return selfDraw && totalDiscards == 0;
    }

    @Override
    public boolean canQiangGang(Player ganger, int tile) {
        return cb.tryQiangGang(ganger, tile);
    }

    @Override
    public boolean canChiDiscard(Player p, int[] seq, int eaten) {
        return ChiBan.canDiscard(p, seq, eaten);
    }

    @Override
    public List<Integer> chiBanAfterEat(Player p, int[] seq, int eaten) {
        return ChiBan.present(p, seq, eaten);
    }

    @Override
    public boolean locksHandWhenReported(Seat seat) {
        return cb.reported(seat);
    }
}
