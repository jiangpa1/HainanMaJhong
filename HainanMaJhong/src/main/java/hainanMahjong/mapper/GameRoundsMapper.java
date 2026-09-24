package hainanMahjong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hainanMahjong.pojo.GameRounds;
import hainanMahjong.vo.recordvo.RoundPlayerRowVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GameRoundsMapper extends BaseMapper<GameRounds> {

    // ==================== 读取（战绩页） ====================

    @Select("SELECT r.round_num AS roundNum, r.is_draw AS isDraw, r.win_type AS winType, "
            + " r.winner_id AS winnerId, r.loser_id AS loserId, r.win_tile AS winTile, "
            + " r.win_hand AS winHand, r.fan_info AS fanInfo, r.total_fan AS totalFan, "
            + " r.dealer AS dealerName, r.wind AS windIdx, "
            + " rc.user_id AS userId, rc.seat AS seat, "
            + " rc.score_change AS scoreChange, rc.is_winner AS isWinner, rc.detail AS detail "
            + "FROM game_rounds r "
            + "JOIN game_round_scores rc ON rc.round_id = r.id "
            + "WHERE r.session_id = #{sessionId} "
            + "ORDER BY r.round_num, rc.seat")
    List<RoundPlayerRowVO> selectRoundsAll(@Param("sessionId") Long sessionId);

    // ==================== 写入（对局落库） ====================

    /**
     * 一把牌里某位玩家的一行流水（对应原 {@code MysqlService.ScoreRow}）。
     *
     * <p>只是传参用的值对象，不是 MyBatis 映射类型。</p>
     */
    record ScoreRow(long userId, int seat, int change, boolean winner, String detail) {
    }

    /**
     * 插入一把对局，返回自增主键。
     *
     * <p>⚠️ {@code win_hand} / {@code fan_info} 是 <b>JSON 列</b>，这里<b>直接传 JSON 字符串</b>，
     * 不走实体 —— MyBatis-Plus 3.5.5 默认没有 {@code Map → JSON} 的 TypeHandler，
     * 走实体会存成 Java 的 {@code toString()}（{@code {a=1}} 而非 {@code {"a":1}}），战绩页会解析失败。</p>
     *
     * @param isDraw 0/1（不用 boolean，避免驱动转换差异）
     */
    @Insert("INSERT INTO game_rounds(session_id, round_num, is_draw, win_type, winner_id, loser_id,"
            + " win_tile, win_hand, fan_info, total_fan, dealer, wind, create_time)"
            + " VALUES(#{sessionId}, #{roundNum}, #{isDraw}, #{winType}, #{winnerId}, #{loserId},"
            + " #{winTile}, #{winHandJson}, #{fanInfoJson}, #{totalFan}, #{dealer}, #{wind}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRound(RoundInsert row);

    /** 按 (session_id, round_num) 找已存在的 round（续跑重放同把时复用）。 */
    @Select("SELECT id FROM game_rounds WHERE session_id = #{sessionId} AND round_num = #{roundNum} LIMIT 1")
    Long findRoundId(@Param("sessionId") Long sessionId, @Param("roundNum") int roundNum);

    /** 清掉某把的流水（重放同把前先清，保证幂等）。 */
    @Delete("DELETE FROM game_round_scores WHERE round_id = #{roundId}")
    int deleteScoresByRound(@Param("roundId") Long roundId);

    /**
     * 写入一条玩家流水。
     *
     * <p>{@code detail} 同为 JSON 列，同样直接传字符串；{@code isWinner} 用 0/1。</p>
     */
    @Insert("INSERT INTO game_round_scores(round_id, user_id, seat, score_change, is_winner, detail)"
            + " VALUES(#{roundId}, #{userId}, #{seat}, #{scoreChange}, #{isWinner}, #{detail})")
    int insertScore(@Param("roundId") Long roundId,
                    @Param("userId") long userId,
                    @Param("seat") int seat,
                    @Param("scoreChange") int scoreChange,
                    @Param("isWinner") int isWinner,
                    @Param("detail") String detail);

    /**
     * {@link #insertRound} 的入参载体。
     *
     * <p>做成 {@code @Data} 风格的普通类而不是 record，是为了让 {@code @Options(useGeneratedKeys)}
     * 能把自增 id 回填到 {@link #id} 上。</p>
     */
    class RoundInsert {
        /** 自增主键，插入后由 MyBatis 回填。 */
        public Long id;
        public Long sessionId;
        public int roundNum;
        /** 0/1。 */
        public int isDraw;
        public int winType;
        public Long winnerId;
        public Long loserId;
        public Integer winTile;
        /** JSON 字符串。 */
        public String winHandJson;
        /** JSON 字符串。 */
        public String fanInfoJson;
        public int totalFan;
        public String dealer;
        public int wind;
    }
}
