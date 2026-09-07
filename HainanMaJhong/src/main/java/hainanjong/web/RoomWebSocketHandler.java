package hainanjong.web;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 处理器：大厅的「创建/加入房间」等待房连接（/room）。
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private final MultiPlayerRoomService roomService;

    public RoomWebSocketHandler(MultiPlayerRoomService roomService) {
        this.roomService = roomService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        roomService.open(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        roomService.onMessage(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        roomService.onClose(session);
    }
}
