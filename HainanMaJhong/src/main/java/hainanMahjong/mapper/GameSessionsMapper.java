package hainanMahjong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.vo.recordvo.PlayerTotalVO;
import hainanMahjong.vo.recordvo.RecordsListVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface GameSessionsMapper extends BaseMapper<GameSessions> {
    /**
     * 用户参与过的房间列表。
     *
     * <p>顺手把 {@code player_ids}（按座位东南西北的四个 userId）也取出来：
     * 一级列表要在房号后面直接列出四家名字，用它翻译比再查一次 game_round_scores
     * 更稳 —— 这一场一局都还没打完（没有 scores 行）时也不会缺名字。</p>
     */
    @Select("SELECT id AS sessionId, room_id AS roomId, start_time AS startTime, "
            + "end_time AS endTime, status AS status, player_ids AS playerIds "
            + "FROM game_sessions "
            + "WHERE JSON_CONTAINS(player_ids, CAST(#{userId} AS JSON)) "
            + "ORDER BY id DESC")
    IPage<RecordsListVO> listSessionsForUser(IPage<RecordsListVO> page, @Param("userId") Long userId);

    /**
     * 一批 session 里各玩家的最终总分与座位（一次查询）。
     * 返回【扁平列表】：每行 = 一个房间 × 一个玩家，sessionId 用于分组。
     *
     * <p><b>⚠️ 必须用 {@code <script>} 包起来。</b>MyBatis 的 XMLLanguageDriver
     * 只在注解内容以 {@code <script>} 开头时才当动态 SQL 解析；否则
     * {@code <foreach>} 会被当成普通字符串拼进 SQL 发给数据库，直接语法错误。
     * 表现就是「战绩查询 → 服务器内部错误」，而且只在【已有战绩】时出现
     * （空列表走的是提前返回那条路，压根不会执行到这里）。</p>
     */
    @Select("<script>"
            + "SELECT r.session_id AS sessionId, rc.user_id AS userId, "
            + "MAX(rc.seat) AS seat, SUM(rc.score_change) AS totalScore "
            + "FROM game_round_scores rc "
            + "JOIN game_rounds r ON r.id = rc.round_id "
            + "WHERE r.session_id IN "
            + "<foreach collection='sessionIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "GROUP BY r.session_id, rc.user_id"
            + "</script>")
    List<PlayerTotalVO> selectTotals(@Param("sessionIds") Collection<Long> sessionIds);

    /** 单场房间码 */
    @Select("SELECT room_id FROM game_sessions WHERE id = #{sessionId}")
    String selectRoomIdById(@Param("sessionId") Long sessionId);

    /** 根据 room_id 反查 session id；同房间码 rematch 会新建 session，故取最新一条（id 最大）。 */
    @Select("SELECT id FROM game_sessions WHERE room_id = #{roomId} "
            + "ORDER BY id DESC LIMIT 1")
    Long selectSessionIdByRoomId(@Param("roomId") String roomId);

    /**
     * 创建整场对局，返回自增 session id。
     *
     * <p>对应原 {@code MysqlService.createSession}：{@code current_round=1, status=0, start_time=NOW()}。</p>
     */
    @Insert("INSERT INTO game_sessions(room_id, creator_id, player_ids, current_round, status, start_time)"
            + " VALUES(#{roomId}, #{creatorId}, #{playerIdsJson}, 1, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertSession(@Param("row") SessionInsert row,
                      @Param("roomId") String roomId,
                      @Param("creatorId") long creatorId,
                      @Param("playerIdsJson") String playerIdsJson);

    /**
     * 玩家中途退出：标记为异常解散（status=2）并记录结束时间。
     *
     * <p>对应原 {@code MysqlService.disbandSession}。</p>
     */
    @Update("UPDATE game_sessions SET status = 2, end_time = NOW() WHERE id = #{sessionId}")
    int disbandSession(@Param("sessionId") Long sessionId);

    /** 推进本场进度到第几把（每把结算后调用）。 */
    @Update("UPDATE game_sessions SET current_round = #{roundNum} WHERE id = #{sessionId}")
    int updateCurrentRound(@Param("sessionId") Long sessionId, @Param("roundNum") int roundNum);

    /** {@link #insertSession} 的入参载体（只为回填自增 id）。 */
    class SessionInsert {
        public Long id;
    }
}
