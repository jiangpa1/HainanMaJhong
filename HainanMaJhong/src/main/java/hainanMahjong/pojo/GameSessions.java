package hainanMahjong.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("game_sessions")
public class GameSessions {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String roomId;
    @TableField("creator_id")
    private Long createId;
    private String playerIds;
    private Long currentRound;
    private int status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
