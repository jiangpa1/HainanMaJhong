package hainanMahjong.monitoring;

import hainanMahjong.room.*;
import hainanMahjong.engine.model.Seat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapacityMetricsTest {
    @Test void confirmsOnlyAllFourCurrentRecipientsWithoutPlayerLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RoomRegistry rooms = new RoomRegistry(); CapacityMetrics metrics = new CapacityMetrics(meters, rooms);
        Room room = new Room("123456", 1); room.state = State.PLAYING;
        for (Seat seat : Seat.values()) {
            WebSocketSession ws = mock(WebSocketSession.class);
            when(ws.getId()).thenReturn("ws-" + seat); when(ws.isOpen()).thenReturn(true);
            Member member = new Member(seat, seat.ordinal() + 1, "player", null); member.gameWs = ws;
            room.members.put(seat, member);
        }
        rooms.register(room); metrics.sampleRooms();
        assertEquals(4, meters.get("mahjong.players.active").gauge().value());
        long seq = metrics.delivery(room, Map.of("type", "end")); assertTrue(seq > 0);
        Member first = room.members.get(Seat.values()[0]);
        metrics.acknowledge(seq, first.gameWs, new Room(room.code, 1));
        assertEquals(1, meters.get("mahjong.ws.delivery.pending").gauge().value());
        for (int i = 0; i < 3; i++) metrics.acknowledge(seq, room.members.get(Seat.values()[i]).gameWs, room);
        assertNull(meters.find("mahjong.ws.delivery.confirmation").timer());
        metrics.acknowledge(seq, room.members.get(Seat.values()[3]).gameWs, room);
        metrics.acknowledge(seq, first.gameWs, room);
        assertEquals(1, meters.get("mahjong.ws.delivery.confirmation").timer().count());
        assertEquals(0, meters.get("mahjong.ws.delivery.pending").gauge().value());
        assertTrue(meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
            .noneMatch(tag -> tag.getValue().contains(room.code) || tag.getValue().contains("ws-")));
        room.state = State.CLOSED; metrics.sampleRooms();
        assertEquals(0, meters.get("mahjong.players.active").gauge().value());
    }
    @Test void doesNotTreatBotTablesAsFourRealPlayers() {
        CapacityMetrics metrics = new CapacityMetrics(new SimpleMeterRegistry(), new RoomRegistry());
        assertEquals(0, metrics.delivery(new Room("123456", 1), Map.of("type", "end")));
    }
}
