package hainanMahjong.engine.port;

import hainanMahjong.rules.HuResult;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.RoundResult;
import hainanMahjong.engine.model.Seat;

/**
 * 什么都不做的 {@link GameListener}。
 *
 * <p>用途有两个：</p>
 * <ul>
 *   <li>{@code RoomManager} 构造时若传入 null，用它兜底（避免每次判空）</li>
 *   <li>测试里只关心"流程能跑通"、不关心事件内容时，直接用它</li>
 * </ul>
 *
 * <p>原来这段是写在 {@code RoomManager} 构造器里的匿名类，占了 34 行。</p>
 */
public class NoopGameListener implements GameListener {

    /** 共享单例：它是无状态的，没必要每次 new。 */
    public static final NoopGameListener INSTANCE = new NoopGameListener();

    @Override
    public void onShuffle(int deckSize) {
    }

    @Override
    public void onDeal(Seat dealer) {
    }

    @Override
    public void onDraw(Seat seat, int tile) {
    }

    @Override
    public void onFlower(Seat seat, int tile) {
    }

    @Override
    public void onDiscard(Seat seat, int tile) {
    }

    @Override
    public void onMeld(Seat seat, Meld meld) {
    }

    @Override
    public void onHu(Seat seat, HuResult result, boolean selfDraw, boolean tianHu,
                     int baoTing, int tile, Seat from) {
    }

    @Override
    public void onTimeout(Seat seat, String action) {
    }

    @Override
    public void onRoundDraw() {
    }

    @Override
    public void onResume() {
    }

    @Override
    public void onEnd(RoundResult result) {
    }
}
