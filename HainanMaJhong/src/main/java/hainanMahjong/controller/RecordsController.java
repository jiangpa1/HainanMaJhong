package hainanMahjong.controller;

import hainanMahjong.common.Result;
import hainanMahjong.dto.PageQueryDTO;
import hainanMahjong.service.RecordsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 战绩查询接口。
 *
 * <ul>
 *   <li>{@code GET /api/records}：我参与过的房间列表（分页，含我在各场的总分与名次）。</li>
 *   <li>{@code GET /api/records/byRoom?roomId=}：按 6 位房间码查该房间明细（局内「战绩」用）。</li>
 *   <li>{@code GET /api/records/{sessionId}}：按 session 查明细。</li>
 * </ul>
 *
 * <p>本类只做「收参数 → 调 Service → 包 Result」；组装在 {@code RecordsServiceImpl}。</p>
 */
@RestController
@RequestMapping("/api/records")
public class RecordsController {

    private final RecordsService recordsService;

    public RecordsController(RecordsService recordsService) {
        this.recordsService = recordsService;
    }

    @GetMapping
    public Result<?> selectRecordsList(@RequestAttribute("userId") Long userId,
                                       @Valid @ModelAttribute PageQueryDTO dto) {
        return Result.success(recordsService.selectRecordsList(userId, dto));
    }

    @GetMapping("/byRoom")
    public Result<?> selectByRoom(@RequestParam String roomId,
                                  @RequestAttribute("userId") Long userId) {
        return Result.success(recordsService.selectByRoom(roomId, userId, true));
    }

    @GetMapping("/{sessionId}")
    public Result<?> selectDetail(@PathVariable long sessionId,
                                  @RequestAttribute("userId") Long userId) {
        return Result.success(recordsService.selectDetail(sessionId, userId, true));
    }
}
