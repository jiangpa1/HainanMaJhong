package hainanMahjong.vo.recordvo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 某玩家在某一场的最终总分与座位。
 *
 * <p>两种用法（同一个 VO，避免出现字段几乎重复的第二套类）：</p>
 * <ul>
 *   <li><b>批量聚合行</b>（列表页）：{@code sessionId} 有值、用于分组；{@code nickname} 为 null；</li>
 *   <li><b>详情页概览</b>：{@code sessionId} 为 null（整个响应只属于一场）；{@code nickname} 已填充。</li>
 * </ul>
 *
 * <p>{@code NON_NULL} 让无关字段不出现在 JSON 里（否则会多出 {@code "sessionId":null}）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlayerTotalVO {

    @JsonProperty("sessionId")
    private Long sessionId;

    @JsonProperty("userId")
    private Long userId;

    /** 座位：0=东 1=南 2=西 3=北。 */
    @JsonProperty("seat")
    private int seat;

    /** 本场累计总分。 */
    @JsonProperty("totalScore")
    private int totalScore;

    @JsonProperty("nickname")
    private String nickname;
}
