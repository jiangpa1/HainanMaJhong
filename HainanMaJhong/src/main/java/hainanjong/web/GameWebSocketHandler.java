package hainanjong.web;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 处理器：每个连接对应一局「1 人类 + 3 机器人」的对局。
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketGameService gameService;

    public GameWebSocketHandler(WebSocketGameService gameService) {
        this.gameService = gameService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        gameService.startGame(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        gameService.onMessage(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        gameService.onClose(session);
    }
}
