package hainanMahjong.room;

import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.rules.HainanConfig;

import java.util.EnumMap;
import java.util.Map;

/**
 * 一块（一轮对局）的起始参数。
 *
 * <p>两种来源：</p>
 * <ul>
 *   <li><b>新开一块</b>：{@code new EngineStart()}，各字段取默认 —— flow 为 null 表示随机首庄新开</li>
 *   <li><b>从 Redis 重建</b>：带上已打到的把数、庄/令/底分流状态与当前把的快照，续跑本块</li>
 * </ul>
 *
 * <p>原先嵌套在 {@code MultiPlayerRoomServiceImpl} 内（private），为了让拆分出去的
 * 引擎模块能构造它而提为顶层 public 类型，字段随之公开。</p>
 */
public final class EngineStart {

    /** 本块的房间配置；null 表示用房间当前的 cfg（或默认配置）。 */
    public HainanConfig cfg;

    /** 非空则用它继续本块；null 则新开（随机首庄）。 */
    public DealerFlow flow;

    /** 本块对应的 game_sessions.id；&lt;= 0 表示还没有，需要新建。 */
    public long sessionId = -1L;

    /** 本块起始时各座位金币（再来一轮时不清空）。 */
    public Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);

    /** 从 Redis 恢复时，当前这一把的快照；null 表示从头开始打。 */
    public GameSnapshot resume = null;
}
