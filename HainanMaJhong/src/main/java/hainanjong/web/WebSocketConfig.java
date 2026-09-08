package hainanjong.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 WebSocket 端点：
 * <ul>
 *   <li>{@code ws://host/room} —— 大厅多人「创建/加入房间」等待房。</li>
 *   <li>{@code ws://host/game} —— 真人联机对局（按座位绑定；可人机补齐，电脑走同一规则）。</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomWebSocketHandler roomHandler;
    private final MultiGameWebSocketHandler multiGameHandler;

    public WebSocketConfig(RoomWebSocketHandler roomHandler,
                           MultiGameWebSocketHandler multiGameHandler) {
        this.roomHandler = roomHandler;
        this.multiGameHandler = multiGameHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomHandler, "/room").setAllowedOrigins("*");
        registry.addHandler(multiGameHandler, "/game").setAllowedOrigins("*");
    }
}
