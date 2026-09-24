package hainanMahjong.room;

import hainanMahjong.engine.human.HumanPlayerController;
import hainanMahjong.engine.model.Action;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.PlayerController;
import hainanMahjong.engine.port.Responder;
import lombok.Setter;

import java.util.List;

/**
 * 可切换的 {@link PlayerController}：在"真人"与"机器人托管"之间换代理。
 *
 * <p>断线/离开时通过 {@link #setDelegate} 换成 {@code BotController}，玩家回来再换回
 * {@link HumanPlayerController} —— 引擎侧始终只看到同一个 controller 实例，
 * 所以<b>换代理不影响正在等待的询问</b>（应答由 {@code HumanPlayerController} 自己的 pending 表管理）。</p>
 *
 * <p>原先嵌套在 {@code MultiPlayerRoomServiceImpl} 内，为了让各拆分模块共用而提为顶层类型。</p>
 */
@Setter
public class SwitchingController implements PlayerController {

    private volatile PlayerController delegate;

    public SwitchingController(PlayerController initial) {
        this.delegate = initial;
    }

    @Override
    public void onDiscardTurn(Seat seat, List<Integer> hand, int drawnTile, List<Integer> banned, Responder r) {
        delegate.onDiscardTurn(seat, hand, drawnTile, banned, r);
    }

    @Override
    public void onActionChance(Seat seat, int tile, List<Action> options, Responder r) {
        delegate.onActionChance(seat, tile, options, r);
    }

    @Override
    public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder r) {
        delegate.onDrawChance(seat, drawnTile, options, r);
    }

    @Override
    public boolean isAutoReport() {
        return delegate.isAutoReport();
    }

    @Override
    public void prepareDiscard(boolean canReport) {
        delegate.prepareDiscard(canReport);
    }
}
