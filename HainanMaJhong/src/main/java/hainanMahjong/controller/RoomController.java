package hainanMahjong.controller;

import hainanMahjong.common.Result;
import hainanMahjong.exception.BusinessException;
import hainanMahjong.service.MultiPlayerRoomService;
import hainanMahjong.service.RoomService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大厅/房间相关查询接口。
 */
@RestController
@RequestMapping("/api/room")
public class RoomController {

    private final MultiPlayerRoomService rooms;
    private final RoomService roomService;

    public RoomController(MultiPlayerRoomService rooms, RoomService roomService) {
        this.rooms = rooms;
        this.roomService = roomService;
    }

    /**
     * 当前用户是否有一个「中途离开但仍在进行」的可返回房间。
     *
     * <p>身份来自 token（{@code JwtInterceptor} 挂在请求上的 userId），不再由调用方传参。</p>
     */
    @GetMapping("/pending")
    public Result<?> pending(@RequestAttribute("userId") Long userId) {
        return Result.success(rooms.pendingReturn(userId == null ? 0L : userId));
    }

    /**
     * 加入房间前的预检：房间码格式、房间是否存在/已解散、是否已开局、是否人满。
     *
     * <p>给大厅用：不通过就<b>原地报错、不跳转</b>。走不通就抛 {@link BusinessException}，
     * 前端拿到的 {@code message} 就是可以直接显示的原因（口径见
     * {@link RoomService#checkJoin}）。</p>
     *
     * <p>通过时返回空对象 {@code {}}，前端只看 code==200。</p>
     */
    @GetMapping("/check")
    public Result<?> check(@RequestAttribute("userId") Long userId,
                           @RequestParam("code") String code) {
        String reason = roomService.checkJoin(userId == null ? 0L : userId,
                code == null ? "" : code.trim());
        if (reason != null) {
            throw new BusinessException(400, reason);
        }
        return Result.success(java.util.Collections.emptyMap());
    }
}
