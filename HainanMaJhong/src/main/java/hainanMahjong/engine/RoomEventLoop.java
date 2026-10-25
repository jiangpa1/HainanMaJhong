package hainanMahjong.engine;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;

/** Per-room mailbox; ready events run on virtual threads. Timers only enqueue events. */
public final class RoomEventLoop implements Executor {
    private static final java.util.concurrent.atomic.LongAdder ACTIVE = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder COMPLETED = new java.util.concurrent.atomic.LongAdder();
    public static long activeEvents() { return ACTIVE.sum(); }
    public static long completedEvents() { return COMPLETED.sum(); }
    private final java.util.concurrent.locks.ReentrantLock mailboxLock = new java.util.concurrent.locks.ReentrantLock();
    private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mahjong-event-", 0).factory());
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "mahjong-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }

    private final Queue<Runnable> mailbox = new ArrayDeque<>();
    private boolean running;

    @Override public void execute(Runnable event) {
        mailboxLock.lock();
        try {
            mailbox.add(event);
            if (!running) {
                running = true;
                WORKERS.execute(this::runOne);
            }
        } finally { mailboxLock.unlock(); }
    }

    private void runOne() {
        Runnable event;
        mailboxLock.lock();
        try { event = mailbox.remove(); } finally { mailboxLock.unlock(); }
        ACTIVE.increment();
        try { event.run(); }
        finally {
            ACTIVE.decrement();
            COMPLETED.increment();
            mailboxLock.lock();
            try {
                if (mailbox.isEmpty()) running = false;
                else WORKERS.execute(this::runOne);
            } finally { mailboxLock.unlock(); }
        }
    }

    public ScheduledFuture<?> schedule(Runnable event, long delayMs) {
        return TIMER.schedule(() -> execute(event), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    static ScheduledExecutorService timer() { return TIMER; }
}
