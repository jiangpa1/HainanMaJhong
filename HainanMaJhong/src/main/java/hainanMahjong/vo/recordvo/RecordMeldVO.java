package hainanMahjong.vo.recordvo;

import lombok.Data;

import java.util.List;

@Data
public class RecordMeldVO {
    private String type;                // CHI/PENG/GANG/AN_GANG/BU_GANG
    private List<Integer> tiles;
}
