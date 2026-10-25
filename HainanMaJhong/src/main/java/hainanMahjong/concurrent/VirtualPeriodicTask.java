package hainanMahjong.concurrent;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.LoggerFactory;

/** Platform clock only dispatches. Each invocation gets a fresh virtual thread; no overlap. */
public final class VirtualPeriodicTask implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor CLOCK = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("mahjong-periodic-clock").factory());
    static { CLOCK.setRemoveOnCancelPolicy(true); }
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService workers;
    private ScheduledFuture<?> deadline;
    private Future<?> running;
    private boolean started, closed;

    public VirtualPeriodicTask(String name) {
        workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-", 0).factory());
    }

    public void start(Runnable task, long initial, long delay, TimeUnit unit) {
        if (delay <= 0) throw new IllegalArgumentException("Delay must be positive");
        lock.lock();
        try {
            if (started || closed) return;
            started = true;
            schedule(task, initial, delay, unit);
        } finally { lock.unlock(); }
    }

    private void schedule(Runnable task, long initial, long delay, TimeUnit unit) {
        deadline = CLOCK.schedule(() -> {
            lock.lock();
            try {
                if (closed) return;
                running = workers.submit(() -> {
                    try { task.run(); }
                    catch (Throwable ex) { LoggerFactory.getLogger(VirtualPeriodicTask.class).warn("Periodic task failed", ex); }
                    finally {
                        lock.lock();
                        try {
                            running = null;
                            if (!closed) schedule(task, delay, delay, unit);
                        } finally { lock.unlock(); }
                    }
                });
            } finally { lock.unlock(); }
        }, initial, unit);
    }

    @Override public void close() {
        lock.lock();
        try {
            closed = true;
            if (deadline != null) deadline.cancel(false);
            if (running != null) running.cancel(true);
            workers.shutdownNow();
        } finally { lock.unlock(); }
    }
}
