package hainanMahjong.vo.recordvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 某一把里的某一位玩家。
 */
@Data
public class RecordParticipantVO {

    private Long userId;

    private String nickname;

    /** 座位：0=东 1=南 2=西 3=北。 */
    private int seat;

    /** 本把得分变化。 */
    private int scoreChange;

    /**
     * 本把是否赢家。
     * ★ 必须显式声明 JSON 名：Lombok 对 {@code boolean isWinner} 生成 {@code isWinner()}，
     * Jackson 会去掉 "is" 前缀序列化成 {@code "winner"}，而前端读的是 {@code p.isWinner}。
     */
    @JsonProperty("isWinner")
    private boolean isWinner;

    /** 是否当前查看者本人（前端据此显示「（我）」）。 */
    @JsonProperty("isMe")
    private boolean isMe;

    /** 杠/花等明细（可空）。 */
    private RecordDetailItemVO detail;
}
