package hainanMahjong.controller;

import hainanMahjong.common.Result;
import hainanMahjong.service.impl.RecordsV2Service;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/records")
public class RecordsV2Controller {
    private final RecordsV2Service service;
    public RecordsV2Controller(RecordsV2Service service) { this.service = service; }

    @GetMapping
    public Result<?> list(@RequestAttribute("userId") long userId,
                          @RequestParam(defaultValue="1") int pageNum,
                          @RequestParam(defaultValue="10") int pageSize,
                          @RequestParam(required=false) Long readThroughSessionId) {
        return Result.success(service.list(userId, pageNum, pageSize, readThroughSessionId));
    }

    @GetMapping("/byRoom")
    public Result<?> byRoom(@RequestAttribute("userId") long userId, @RequestParam String roomId) {
        return Result.success(service.byRoom(roomId, userId));
    }

    @GetMapping("/{sessionId}/overview")
    public Result<?> overview(@RequestAttribute("userId") long userId, @PathVariable long sessionId,
                              @RequestParam(required=false) Integer readThroughRoundNum) {
        return Result.success(service.overview(sessionId, userId, readThroughRoundNum));
    }

    @GetMapping({"/{sessionId}/rounds", "/{sessionId}/rounds/new"})
    public Result<?> rounds(@RequestAttribute("userId") long userId, @PathVariable long sessionId,
                            @RequestParam(defaultValue="0") int afterRoundNum,
                            @RequestParam(defaultValue="10") int pageSize,
                            @RequestParam(required=false) Integer readThroughRoundNum) {
        return Result.success(service.rounds(sessionId, userId, afterRoundNum, pageSize, readThroughRoundNum));
    }

    @GetMapping("/{sessionId}/rounds/{roundNum}")
    public Result<?> detail(@RequestAttribute("userId") long userId, @PathVariable long sessionId,
                            @PathVariable int roundNum, @RequestParam(required=false) Integer readThroughRoundNum) {
        return Result.success(service.detail(sessionId, userId, roundNum, readThroughRoundNum));
    }
}
