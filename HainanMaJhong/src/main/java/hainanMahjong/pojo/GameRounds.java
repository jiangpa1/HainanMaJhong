package hainanMahjong.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("game_rounds")
public class GameRounds {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private int roundNum;
    private int isDraw;
    private int winType;
    private Long winnerId;
    private Long loserId;
    private int winTile;
    private Map<String, Object> winHand;
    private Map<String, Integer> fanInfo;
    private int totalFan;
    private LocalDateTime createTime;
    private String dealer;
    private int wind;

}
