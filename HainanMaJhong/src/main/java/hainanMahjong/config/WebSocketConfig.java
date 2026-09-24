package hainanMahjong.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import hainanMahjong.websocket.GameWebSocketHandler;
import hainanMahjong.websocket.RoomWebSocketHandler;
import hainanMahjong.websocket.WsAuthHandshakeInterceptor;

/**
 * 注册 WebSocket 端点：
 * <ul>
 *   <li>{@code ws://host/room} —— 大厅多人「创建/加入房间」等待房。</li>
 *   <li>{@code ws://host/game} —— 真人联机对局（按座位绑定；可人机补齐，电脑走同一规则）。</li>
 * </ul>
 *
 * <p>两个端点都必须挂上 {@link WsAuthHandshakeInterceptor}：
 * 身份只认握手时校验出来的 token，URL 上的 {@code userId} 不再被信任。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomWebSocketHandler roomHandler;
    private final GameWebSocketHandler gameWebSocketHandler;
    private final WsAuthHandshakeInterceptor authHandshakeInterceptor;

    public WebSocketConfig(RoomWebSocketHandler roomHandler,
                           GameWebSocketHandler gameWebSocketHandler,
                           WsAuthHandshakeInterceptor authHandshakeInterceptor) {
        this.roomHandler = roomHandler;
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomHandler, "/room")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins("*");

        registry.addHandler(gameWebSocketHandler, "/game")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
