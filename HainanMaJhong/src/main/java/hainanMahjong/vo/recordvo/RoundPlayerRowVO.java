package hainanMahjong.vo.recordvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 战绩明细的 SQL 行：<b>一行 = 一把 × 一位玩家</b>。
 *
 * <p>来自 {@code game_rounds JOIN game_round_scores}，所以：</p>
 * <ul>
 *   <li><b>局级字段</b>（roundNum/isDraw/winType/winnerId/loserId/winTile/winHand/fanInfo/
 *       totalFan/dealerName/windIdx）在同一把的多行里是<b>重复</b>的 —— 按 roundNum 分组后取第一行；</li>
 *   <li><b>人级字段</b>（userId/seat/scoreChange/isWinner/detail）每行不同。</li>
 * </ul>
 *
 * <p>三个 JSON 列保持 String：写入侧就是 JSON 字符串，读出后由 Service 解析，无需 TypeHandler。</p>
 */
@Data
public class RoundPlayerRowVO {

    // ==================== 局级（同组内重复） ====================

    private int roundNum;

    /** 是否流局。★ 保留 is 前缀。 */
    @JsonProperty("isDraw")
    private boolean isDraw;

    /** 1=点炮；其它=自摸/荒庄。 */
    private Integer winType;

    private Long winnerId;

    private Long loserId;

    /** 胡的那张牌下标；荒庄为 null。 */
    private Integer winTile;

    /** JSON 原文：{@code {hand:[...], melds:[{type,tiles}]}}。 */
    private String winHand;

    /** JSON 原文：{@code {番型枚举名: 倍数}}。 */
    private String fanInfo;

    /**
     * 本把番数：<b>倍数和</b>（不是番型个数）。
     *
     * <p>挑「我最高番」时用它比较：用番型个数的话，"七对+混一色+自摸"(3) 会压过
     * "十三幺"(1)，口径就反了。</p>
     */
    private Integer totalFan;

    /** 庄家座位名：EAST/SOUTH/WEST/NORTH。 */
    private String dealerName;

    /** 令 0东 1南 2西 3北。 */
    private Integer windIdx;

    // ==================== 人级（每行不同） ====================

    private Long userId;

    private int seat;

    private int scoreChange;

    /** 是否本把赢家。★ 保留 is 前缀。 */
    @JsonProperty("isWinner")
    private boolean isWinner;

    /** JSON 原文：{@code {notes:[], flowers:[], gangs:[{type,tiles}]}}。 */
    private String detail;
}
