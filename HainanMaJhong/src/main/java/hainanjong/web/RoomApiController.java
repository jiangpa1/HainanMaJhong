package hainanjong.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 大厅/房间相关查询接口。
 */
@RestController
@RequestMapping("/api/room")
public class RoomApiController {

    private final MultiPlayerRoomService rooms;

    public RoomApiController(MultiPlayerRoomService rooms) {
        this.rooms = rooms;
    }

    /** 当前用户是否有一个“中途离开但仍在进行”的可返回房间。 */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam(value = "userId", defaultValue = "0") long userId) {
        return rooms.pendingReturn(userId);
    }
}
