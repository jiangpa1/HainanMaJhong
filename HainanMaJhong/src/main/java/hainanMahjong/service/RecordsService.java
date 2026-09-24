package hainanMahjong.service;

import hainanMahjong.common.PageResult;
import hainanMahjong.dto.PageQueryDTO;
import hainanMahjong.vo.recordvo.RecordDetailVO;
import hainanMahjong.vo.recordvo.RecordsListVO;

import javax.validation.Valid;

public interface RecordsService {
    PageResult<RecordsListVO> selectRecordsList(long userId, @Valid PageQueryDTO dto);

    RecordDetailVO selectByRoom(String roomId, Long userId, boolean needNotFoundFlag);

    RecordDetailVO selectDetail(long sessionId, Long userId, boolean needNotFoundFlag);
}
