package hainanMahjong.engine;

import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 引擎的"带超时询问"机制。
 *
 * <p><b>这是整个项目最微妙的并发点</b>，抽出来单独成类是为了让它能被看懂、被测试：</p>
 *
 * <ul>
 *   <li>引擎线程调 {@link #prompt} 后<b>阻塞</b>在 {@link CountDownLatch#await()} 上；</li>
 *   <li>应答来自<b>另外两条线程</b>之一：
 *       WebSocket 线程（玩家出牌）或 {@code mahjong-timer} 线程（超时）。</li>
 * </ul>
 *
 * <p>用 {@code CountDownLatch(1)} + 一次性开关（{@code done[0]}）保证
 * <b>先到者生效、后到者被丢弃</b> —— 玩家在超时后一秒才出牌，那次出牌会被安全忽略。</p>
 *
 * <p>{@code pendingForce} 保存当前这把的"强制应答"闭包，供 {@link #forceNow()}
 * 在 {@code cancel()} 时立即唤醒引擎线程，避免它一直等到超时。</p>
 *
 * <p>从 {@code RoomManager} 抽出，逻辑逐字保留。</p>
 */
public class PromptSupport {

    /** 定时器；为 null 时不启用超时（只等应答）。 */
    private final ScheduledExecutorService scheduler;

    /** 超时需要通知的监听器。 */
    private final GameListener listener;

    /** 当前这把询问的"强制应答"闭包；无询问时为 null。 */
    private volatile Runnable pendingForce;

    public PromptSupport(ScheduledExecutorService scheduler, GameListener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /**
     * 向控制器发起一次决策请求，带超时。控制器通过 {@code reply} 回调应答；
     * 超时则用 {@code forced} 强制值应答。
     *
     * @param seat      本回合的座位（仅用于超时通知）
     * @param what      动作名（如"出牌"），仅用于超时通知
     * @param timeoutMs 超时毫秒；&lt;=0 或 scheduler 为空则不启用超时
     * @param body      发起询问的逻辑：拿到的 {@code Consumer} 就是应答入口
     * @param forced    超时后使用的强制值
     */
    public <T> T prompt(Seat seat, String what, long timeoutMs,
                        Consumer<Consumer<T>> body, T forced) {
        final Object lock = new Object();
        final boolean[] done = {false};
        final Object[] box = new Object[1];
        final boolean[] forcedFlag = {false};
        final CountDownLatch latch = new CountDownLatch(1);

        final Consumer<T> reply = value -> {
            synchronized (lock) {
                if (done[0]) return;
                done[0] = true;
                box[0] = value;
            }
            latch.countDown();
        };

        final Runnable force = () -> {
            synchronized (lock) {
                if (done[0]) return;
                done[0] = true;
                box[0] = forced;
                forcedFlag[0] = true;
            }
            latch.countDown();
        };
        pendingForce = force;

        ScheduledFuture<?> f = null;
        if (scheduler != null && timeoutMs > 0) {
            f = scheduler.schedule(force, timeoutMs, TimeUnit.MILLISECONDS);
        }
        try {
            body.accept(reply);
        } catch (Throwable ex) {
            force.run();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (f != null) {
            f.cancel(false);
        }
        pendingForce = null;
        if (forcedFlag[0]) {
            listener.onTimeout(seat, what);
        }
        @SuppressWarnings("unchecked")
        T r = (T) box[0];
        return r;
    }

    /**
     * 立即用"强制值"唤醒引擎线程（{@code cancel()} 时调用）。
     *
     * <p>当前没有询问时是 no-op。</p>
     */
    public void forceNow() {
        Runnable f = pendingForce;
        if (f != null) {
            f.run();
        }
    }

    /** 关闭内部定时器（每把结束时调用）。 */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
