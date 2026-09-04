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

    void onEnd(RoundResult result);
}
