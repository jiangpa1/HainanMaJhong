package hainanMahjong.vo.recordvo;

import lombok.Data;

import java.util.List;

@Data
public class RecordDetailItemVO {
    private List<String> notes;   // 例如 ["明杠×1","真花×2"]
    private List<String> flowers; // 花牌
    private List<RecordMeldVO> gangs; // 杠牌（前端也用它判"有杠"）
}
