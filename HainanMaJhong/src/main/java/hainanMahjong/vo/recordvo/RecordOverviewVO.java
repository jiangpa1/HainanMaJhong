package hainanMahjong.vo.recordvo;

import lombok.Data;

import java.util.List;

@Data
public class RecordOverviewVO {
    private int winCount;//胡牌次数
    private String highestFan;//最高番型
    private List<PlayerTotalVO> players;//本场四家总分
}
