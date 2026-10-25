package hainanMahjong.websocket;

import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Same lock for sequence allocation, frame batches and writes; keys do not retain closed sessions. */
public final class SessionSendLocks {
    private static final Map<WebSocketSession, ReentrantLock> LOCKS = new WeakHashMap<>();
    private SessionSendLocks() { }
    public static ReentrantLock lock(WebSocketSession session) {
        // Only an in-memory map operation holds this monitor. Acquire/send AFTER returning.
        synchronized (LOCKS) { return LOCKS.computeIfAbsent(session, key -> new ReentrantLock()); }
    }
}
