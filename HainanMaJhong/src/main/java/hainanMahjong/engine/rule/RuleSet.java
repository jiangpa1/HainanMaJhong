package hainanMahjong.engine.rule;

import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.vo.recordvo.RulesCallbacks;

import java.util.List;

/**
 * 一套麻将规则。
 *
 * <p>{@code RoomManager} 原来有 <b>16 处</b> {@code if (config.hainan)} 分支，散落在回合主流程里。
 * 本接口把其中<b>可干净收口的 9 处</b>换成"问规则集" ——
 * 读 {@code RoomManager} 时不再需要一边读流程一边判断"这段是不是海南才有"。</p>
 *
 * <p><b>两个实现</b>：{@link StandardRules}（{@code hainan=false}）与 {@link HainanRules}（{@code hainan=true}）。</p>
 *
 * <p><b>没有 {@code isHainan()} 这种开关</b>：那样只是把 if 从调用方挪到实现里，没解决问题。
 * 每个方法都是<b>有语义的判断</b>。</p>
 *
 * <p><b>刻意没有覆盖的分支</b>：{@code requestDiscard} 里的报听流程、{@code detectDiscardActions}
 * 的报听分支、{@code applyDrawAction} 的抢杠提前 return —— 这几处与回合控制流深度交织，
 * 硬抽成接口只会更难看，保留内联判断。</p>
 */
public interface RuleSet {

    /** 依据配置挑一套规则。{@code cb} 供规则集反向调用引擎（发询问、读报听状态）。 */
    static RuleSet of(GameConfig config, RulesCallbacks cb) {
        return config.hainan ? new HainanRules(cb, config) : new StandardRules(cb, config);
    }

    // ==================== 牌墙与流局 ====================

    /**
     * 是否因为牌墙见底而必须流局（荒庄）。
     *
     * <p>海南：牌墙剩 {@code WALL_END}(15) 张时荒庄。标准规则：不设这条线，
     * 牌墙真的空了由 {@code takeFromWall()} 返回 -1 收束。</p>
     */
    boolean shouldEndDraw(int deckSize);

    // ==================== 胡牌 ====================

    /**
     * 只看暗牌判断能否成胡。
     *
     * <p>标准规则：{@code concealed} 的结构合法性即可。海南：结构合法 <b>且</b>有番
     * （受 {@code fanGate} 控制），并把令风/门风/花牌计入番型。</p>
     *
     * @param concealed 暗牌计数数组（34 张牌型，已含 {@code extraTile}）——由引擎组装
     */
    boolean canHu(int[] concealed, Player p, int extraTile, boolean selfDraw, boolean qiangGang);

    /** 天胡：开局首轮、无人出过牌时的自摸。仅海南承认。 */
    boolean isTianHu(boolean selfDraw, int totalDiscards);

    // ==================== 抢杠 ====================

    /**
     * 有人补杠时是否要发起抢杠询问。
     *
     * <p>仅海南启用；标准规则直接返回 false（不询问、不抢杠）。</p>
     */
    boolean canQiangGang(Player ganger, int tile);

    // ==================== 吃 ====================

    /**
     * 用 {@code seq} 吃这张牌后，手里是否还有牌可打。
     *
     * <p>海南的"吃后禁打"要求：如果吃完后<b>所有能打的牌都被禁</b>，则这次吃不允许。
     * 标准规则不做这个检查，恒为 true。</p>
     */
    boolean canChiDiscard(Player p, int[] seq, int eaten);

    /**
     * 吃完之后这一手的禁打牌。
     *
     * <p>标准规则返回 {@code null}，表示"没有任何限制"；海南返回
     * {@link ChiBan#present} 的结果。</p>
     */
    List<Integer> chiBanAfterEat(Player p, int[] seq, int eaten);

    // ==================== 报听 ====================

    /**
     * 已报听的座位是否锁手（抓到什么打什么，不能换牌）。
     *
     * <p>仅海南启用。</p>
     */
    boolean locksHandWhenReported(Seat seat);
}
