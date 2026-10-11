package hainanMahjong.mapper;

import hainanMahjong.vo.recordvo.RecordsListVO;
import hainanMahjong.vo.recordvo.RoundPlayerRowVO;
import hainanMahjong.vo.recordvo.RecordsV2VO.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RecordsV2Mapper {
    String MEMBERSHIP = "JSON_CONTAINS(player_ids, CAST(#{userId} AS JSON))";

    @Select("SELECT COALESCE(MAX(id),0) FROM game_sessions WHERE " + MEMBERSHIP)
    long latestSession(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM game_sessions WHERE " + MEMBERSHIP + " AND id <= #{cutoff}")
    long sessionCount(@Param("userId") long userId, @Param("cutoff") long cutoff);

    @Select("SELECT id AS sessionId, room_id AS roomId, start_time AS startTime, end_time AS endTime, "
            + "status, player_ids AS playerIds FROM game_sessions WHERE " + MEMBERSHIP
            + " AND id <= #{cutoff} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<RecordsListVO> sessions(@Param("userId") long userId, @Param("cutoff") long cutoff,
                                @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COALESCE(MAX(round_num),0) FROM game_rounds WHERE session_id=#{sessionId}")
    int latestRound(@Param("sessionId") long sessionId);

    // 数据库聚合，只返回四家固定大小统计；持久化汇总与索引调优见计划 5.1/5.2。
    @Select("SELECT s.user_id AS userId, MAX(s.seat) AS seat, SUM(s.score_change) AS totalScore, "
            + "SUM(CASE WHEN s.is_winner=1 THEN 1 ELSE 0 END) AS winCount, "
            + "MAX(CASE WHEN s.is_winner=1 THEN COALESCE(r.total_fan,0) ELSE 0 END) AS highestFanValue "
            + "FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id "
            + "WHERE r.session_id=#{sessionId} AND r.round_num > #{after} AND r.round_num <= #{cutoff} GROUP BY s.user_id")
    List<Stats> stats(@Param("sessionId") long sessionId, @Param("after") int after, @Param("cutoff") int cutoff);

    @Select("SELECT r.fan_info FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id "
            + "WHERE r.session_id=#{sessionId} AND r.round_num > #{after} AND r.round_num <= #{cutoff} AND s.user_id=#{userId} "
            + "AND s.is_winner=1 AND r.total_fan > 0 ORDER BY r.total_fan DESC, r.round_num ASC LIMIT 1")
    String highestFan(@Param("sessionId") long sessionId, @Param("after") int after, @Param("cutoff") int cutoff,
                      @Param("userId") long userId);

    // LIMIT 作用于局 ID，不作用于四家分数。多取一局仅用于判断 hasMore。
    @Select("SELECT id, round_num AS roundNum FROM game_rounds WHERE session_id=#{sessionId} "
            + "AND round_num > #{after} AND round_num <= #{cutoff} ORDER BY round_num ASC LIMIT #{limit}")
    List<RoundKey> keys(@Param("sessionId") long sessionId, @Param("after") int after,
                        @Param("cutoff") int cutoff, @Param("limit") int limit);

    String IDS = "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";

    @Select("<script>SELECT id AS roundId, round_num AS roundNum, is_draw AS draw, win_type AS winType, "
            + "winner_id AS winnerId, loser_id AS loserId, total_fan AS totalFan, dealer AS dealerSeat, "
            + "wind AS windIdx FROM game_rounds WHERE session_id=#{sessionId} AND id IN " + IDS
            + " ORDER BY round_num</script>")
    List<RoundSummary> summaries(@Param("sessionId") long sessionId, @Param("ids") List<Long> ids);

    @Select("<script>SELECT round_id AS roundId, user_id AS userId, seat, score_change AS scoreChange, "
            + "is_winner AS winner FROM game_round_scores WHERE round_id IN " + IDS
            + " ORDER BY round_id, seat</script>")
    List<Score> scores(@Param("ids") List<Long> ids);

    @Select("SELECT r.round_num AS roundNum, r.is_draw AS isDraw, r.win_type AS winType, "
            + "r.winner_id AS winnerId, r.loser_id AS loserId, r.win_tile AS winTile, r.win_hand AS winHand, "
            + "r.fan_info AS fanInfo, r.total_fan AS totalFan, r.dealer AS dealerName, r.wind AS windIdx, "
            + "s.user_id AS userId, s.seat, s.score_change AS scoreChange, s.is_winner AS isWinner, s.detail "
            + "FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id "
            + "WHERE r.session_id=#{sessionId} AND r.round_num=#{roundNum} AND r.round_num <= #{cutoff} ORDER BY s.seat")
    List<RoundPlayerRowVO> detail(@Param("sessionId") long sessionId, @Param("roundNum") int roundNum,
                                 @Param("cutoff") int cutoff);
}
