package hainanjong.game;

import java.util.List;

/**
 * 玩家决策接口（由 UI / 机器人 / 测试脚本实现）。
 *
 * <p>所有方法都必须通过 {@link Responder} 应答；若在超时时间内未应答，
 * 房间管理器会强制动作（强制出牌，或视为“过”）。</p>
 */
public interface PlayerController {

    /**
     * 轮到 {@code seat} 出牌（此时手牌 14 张）。
     *
     * @param seat      座位
     * @param hand      当前手牌（副本，已排序）
     * @param responder 通过 {@code responder.discard(tile)} 应答
     */
    void onDiscardTurn(Seat seat, List<Integer> hand, int drawnTile, Responder responder);

    /**
     * {@code seat} 可以对别人打出的 {@code discardedTile} 做出反应。
     *
     * @param options 可选动作（已按优先级排序：胡 > 杠 > 碰 > 吃）
     */
    void onActionChance(Seat seat, int discardedTile, List<Action> options, Responder responder);

    /**
     * {@code seat} 摸牌后，可以选择自摸胡 / 暗杠 / 补杠，否则应 {@code responder.pass()} 后出牌。
     */
    void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder responder);

    /**
     * 是否由引擎自动报听（海南天听/地听）。机器人/托管返回 true；真人返回 false（走界面按钮）。
     */
    default boolean isAutoReport() {
        return false;
    }

    /** 出牌请求发出前，引擎把“本次能否报听”的提示交给控制器（真人据此弹按钮）。 */
    default void prepareDiscard(boolean canReport) {
    }
}
