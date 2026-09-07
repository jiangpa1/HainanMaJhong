package hainanjong.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 WebSocket 端点：
 * <ul>
 *   <li>{@code ws://host/ws} —— 单人「快速开始」对局（1 真人 + 3 机器人）。</li>
 *   <li>{@code ws://host/room} —— 大厅多人「创建/加入房间」等待房。</li>
 *   <li>{@code ws://host/game} —— 4 人真人对局（按座位绑定）。</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;
    private final RoomWebSocketHandler roomHandler;
    private final MultiGameWebSocketHandler multiGameHandler;

    public WebSocketConfig(GameWebSocketHandler handler, RoomWebSocketHandler roomHandler,
                           MultiGameWebSocketHandler multiGameHandler) {
        this.handler = handler;
        this.roomHandler = roomHandler;
        this.multiGameHandler = multiGameHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
        registry.addHandler(roomHandler, "/room").setAllowedOrigins("*");
        registry.addHandler(multiGameHandler, "/game").setAllowedOrigins("*");
    }
}
