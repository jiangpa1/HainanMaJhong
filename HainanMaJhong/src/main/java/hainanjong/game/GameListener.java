package hainanjong.game;

import hainanjong.HuResult;

/**
 * 牌局事件监听器（用于日志 / UI 展示）。
 */
public interface GameListener {
    void onShuffle(int deckSize);

    void onDeal(Seat dealer);

    void onDraw(Seat seat, int tile);

    void onFlower(Seat seat, int tile);

    void onDiscard(Seat seat, int tile);

    void onMeld(Seat seat, Meld meld);

    void onHu(Seat seat, HuResult result, boolean selfDraw, int tile, Seat from);

    void onTimeout(Seat seat, String action);

    void onRoundDraw();

    /** 从快照恢复现场后回调（用于向前端推送完整状态）。 */
    void onResume();

    void onEnd(RoundResult result);

    /**
     * 某座位进入一次决策回合（出牌/吃碰杠胡/自摸杠）时回调，
     * 供“多端广播当前轮到谁 + 倒计时”使用；未实现者忽略。
     */
    default void onTurnStart(Seat seat, String kind, long timeoutMs) {
    }
}
