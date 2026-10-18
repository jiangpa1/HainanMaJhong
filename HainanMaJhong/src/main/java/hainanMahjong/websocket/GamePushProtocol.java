package hainanMahjong.websocket;

import org.springframework.web.socket.WebSocketSession;

/** Runtime-only /game negotiation and synchronization state; never persisted to Redis. */
public final class GamePushProtocol {
    public static final int VERSION = 1;
    public static final String MODE = "game.push.mode";
    public static final String SEQUENCE = "game.push.sequence";
    public static final String RESYNC = "game.push.resync";
    public static final String LAST_REQUEST = "game.push.lastRequest";
    public static final String NEXT_SNAPSHOT = "game.push.nextSnapshot";

    private GamePushProtocol() {}

    public static boolean delta(WebSocketSession session) {
        return session != null && "delta".equals(session.getAttributes().get(MODE));
    }
}
