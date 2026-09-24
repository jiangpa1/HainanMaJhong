package hainanMahjong.vo.recordvo;

import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.rule.RuleSet;

/**
 * {@link RuleSet} 反过来询问引擎的窄接口。
 *
 * <p>规则集里有几件事不能纯函数化 —— 它们要么要跟玩家打交道，要么要读引擎的即时状态：</p>
 * <ul>
 *   <li>抢杠要发起"要不要抢杠?"询问</li>
 *   <li>"是否已报听"是引擎维护的状态</li>
 *   <li>门风/令风来自当局配置</li>
 * </ul>
 *
 * <p>与其把这些在 {@code RoomManager} 上开成 {@code public}，不如让规则集持有这个
 * <b>只有 6 个方法</b>的回调 —— 引擎的对外契约因此保持不变。</p>
 */
public interface RulesCallbacks {

    /** 只看暗牌判断能否成胡（结构合法即可，不看番）。 */
    boolean canHu(int[] concealed);

    /** 能否胡这一张：{@code selfDraw} 自摸 / {@code qiangGang} 抢杠。 */
    boolean canHuWin(Player p, int extraTile, boolean selfDraw, boolean qiangGang);

    /** 有人要补杠：向其余三家发起抢杠询问；有人抢胡则本把直接结束并返回 true。 */
    boolean tryQiangGang(Player ganger, int tile);

    /** 该座位是否已报听（0 无 / 1 天听 / 2 地听，非 0 即已报）。 */
    boolean reported(Seat seat);

    /** 庄家座位（用于门风计算）。 */
    Seat dealer();

    /** 当前"令"风 0东 1南 2西 3北。 */
    int roundWindIdx();
}
