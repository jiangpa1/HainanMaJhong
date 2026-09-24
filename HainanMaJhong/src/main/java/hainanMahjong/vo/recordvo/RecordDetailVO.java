package hainanMahjong.vo.recordvo;

import lombok.Data;

import java.util.List;

@Data
public class RecordDetailVO {
    private String roomId;
    private Boolean notFound;
    private RecordOverviewVO overview;//概览（当前用户视角）
    private List<RecordRoundVO> rounds;//每一把情况
}
