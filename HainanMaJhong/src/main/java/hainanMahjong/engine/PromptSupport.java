package hainanMahjong.engine;

import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A decision completes once; replies and deadlines continue on the owning mailbox. */
public class PromptSupport {
    private final java.util.concurrent.locks.ReentrantLock decisionLock = new java.util.concurrent.locks.ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final GameListener listener;
    private final Executor events;
    private final boolean ownsScheduler;
    private boolean closed;
    private Pending<?> pending;

    public PromptSupport(ScheduledExecutorService scheduler, GameListener listener) {
        this(scheduler, listener, Runnable::run, true);
    }

    public PromptSupport(GameListener listener, Executor events) {
        this(RoomEventLoop.timer(), listener, events, false);
    }

    private PromptSupport(ScheduledExecutorService scheduler, GameListener listener,
                          Executor events, boolean ownsScheduler) {
        this.scheduler = scheduler;
        this.listener = listener;
        this.events = events;
        this.ownsScheduler = ownsScheduler;
        if (scheduler instanceof ScheduledThreadPoolExecutor executor) executor.setRemoveOnCancelPolicy(true);
    }

    private final class Pending<T> {
        final Seat seat;
        final String what;
        final T forced;
        final Consumer<T> continuation;
        ScheduledFuture<?> timer;
        boolean done;
        Pending(Seat seat, String what, T forced, Consumer<T> continuation) {
            this.seat = seat; this.what = what; this.forced = forced; this.continuation = continuation;
        }
        void complete(T value, boolean timeout) {
            decisionLock.lock();
            try {
                if (done) return;
                done = true;
                if (timer != null) timer.cancel(false);
                if (pending == this) pending = null;
            } finally { decisionLock.unlock(); }
            events.execute(() -> {
                if (timeout) listener.onTimeout(seat, what);
                continuation.accept(value);
            });
        }
        void force() { complete(forced, true); }
    }

    public <T> void promptAsync(Seat seat, String what, long timeoutMs,
                                Consumer<Consumer<T>> body, T forced, Consumer<T> continuation) {
        Pending<T> request = new Pending<>(seat, what, forced, continuation);
        decisionLock.lock();
        try {
            if (closed) { events.execute(() -> continuation.accept(forced)); return; }
            if (pending != null) throw new IllegalStateException("A decision is already pending");
            pending = request;
            if (scheduler != null && timeoutMs > 0) {
                try { request.timer = scheduler.schedule(request::force, timeoutMs, TimeUnit.MILLISECONDS); }
                catch (RejectedExecutionException e) { request.force(); }
            }
        } finally { decisionLock.unlock(); }
        try {
            listener.onIdle();
            body.accept(value -> request.complete(value, false));
        } catch (Throwable ex) { request.force(); }
    }

    /** Standalone compatibility adapter. Multiplayer uses promptAsync. */
    public <T> T prompt(Seat seat, String what, long timeoutMs, Consumer<Consumer<T>> body, T forced) {
        CompletableFuture<T> result = new CompletableFuture<>();
        promptAsync(seat, what, timeoutMs, body, forced, result::complete);
        return result.join();
    }

    public int pendingTimerCount() {
        decisionLock.lock();
        try {
            return pending != null && pending.timer != null && !pending.timer.isDone() ? 1 : 0;
        } finally { decisionLock.unlock(); }
    }

    public void forceNow() {
        Pending<?> request;
        decisionLock.lock();
        try { request = pending; } finally { decisionLock.unlock(); }
        if (request != null) request.force();
    }

    public void shutdown() {
        decisionLock.lock();
        try { closed = true; } finally { decisionLock.unlock(); }
        forceNow();
        if (ownsScheduler && scheduler != null) scheduler.shutdownNow();
    }
}
