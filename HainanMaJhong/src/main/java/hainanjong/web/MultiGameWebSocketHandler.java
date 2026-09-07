package hainanjong.web;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 处理器：4 人真人对局连接（/game?userId=&amp;code=&amp;seat=）。
 * 等待房 room.html 收到 start 后跳到 game.html?mode=multi… 并用本端点连入座位。
 */
@Component
public class MultiGameWebSocketHandler extends TextWebSocketHandler {

    private final MultiPlayerRoomService roomService;

    public MultiGameWebSocketHandler(MultiPlayerRoomService roomService) {
        this.roomService = roomService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        roomService.openGame(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        roomService.onGameMessage(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        roomService.onGameClose(session);
    }
}
