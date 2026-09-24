package hainanMahjong.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Map;

@Data
@TableName("game_round_scores")
public class GameRoundScores {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long roundId;
    private Long userId;
    private int seat;
    private int scoreChange;
    private int isWinner;
    private Map<String, Object> detail;
}
