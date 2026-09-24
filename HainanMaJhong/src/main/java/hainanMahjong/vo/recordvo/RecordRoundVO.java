package hainanMahjong.vo.recordvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 一把牌的完整明细（含本把四家）。
 */
@Data
public class RecordRoundVO {

    /** 第几把（从 1 起）。 */
    private int roundNum;

    /**
     * 是否流局（荒庄）。
     * ★ 必须显式声明 JSON 名：Lombok 对 {@code boolean isDraw} 生成 {@code isDraw()}，
     * Jackson 会去掉 "is" 前缀序列化成 {@code "draw"}，而前端读的是 {@code r.isDraw}。
     */
    @JsonProperty("isDraw")
    private boolean isDraw;

    /** 1=点炮；其它值=自摸/荒庄。 */
    private Integer winType;

    private Long winnerId;

    /** 赢家昵称。 */
    private String winner;

    private Long loserId;

    /** 点炮者昵称。 */
    private String loser;

    /** 胡的那张牌下标；荒庄为 null。 */
    private Integer winTile;

    /** 番型中文列表；空 → 前端展示「平胡」。 */
    private List<String> fanTypes;

    /** 胡牌时的暗牌手牌（含胡的那张）。 */
    private List<Integer> winHand;

    /** 胡牌时的副露（杠按 4 张）。 */
    private List<RecordMeldVO> winMelds;

    /** 本把庄家座位名：EAST/SOUTH/WEST/NORTH。 */
    private String dealerSeat;

    /** 令 0东 1南 2西 3北。 */
    private Integer windIdx;

    /** 庄家昵称（由 participants 里同座位者得到）。 */
    private String dealerNickname;

    /** 本把四家。 */
    private List<RecordParticipantVO> participants;
}
