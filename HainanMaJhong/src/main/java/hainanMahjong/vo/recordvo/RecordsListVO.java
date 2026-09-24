package hainanMahjong.vo.recordvo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RecordsListVO {
    private Long sessionId;
    private String roomId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int status;
    private int totalScore;
    private int rank;

    /**
     * 本场四家的 userId（JSON 数组，按座位 东→南→西→北），只在服务端解析用。
     *
     * <p>{@code @JsonIgnore}：这是给"把 id 翻译成名字"用的中间数据，
     * 没必要（也不该）把原始 id 列表下发到前端。</p>
     */
    @JsonIgnore
    private String playerIds;

    /**
     * 本场四家的显示名，按座位 东→南→西→北。
     *
     * <p>战绩一级列表在房号后面直接展示这四个名字，点都不用点进去。</p>
     */
    private List<String> players;
}
