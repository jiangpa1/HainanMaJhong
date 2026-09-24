package hainanMahjong.service;

import hainanMahjong.service.impl.RoundPersister;
import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import hainanMahjong.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对局落库的<b>连库</b>集成测试：验证 MyBatis Mapper 版的写入与原 JDBC 版行为一致。
 *
 * <p><b>为什么必须有它</b>：{@code RoomManagerSmokeTest} 跑 400 把是纯内存的，
 * {@code saveHand} 只在有 sessionId 时被调用 —— 所以引擎回归测试<b>完全覆盖不到写库</b>。
 * 而这次迁移的主要风险恰恰在"SQL 与 JSON 列"。</p>
 *
 * <p><b>验证的四件事</b>：</p>
 * <ol>
 *   <li>{@code createSession} 能写进 {@code game_sessions}，且 <b>{@code creator_id} 列名对得上</b>
 *       （实体字段叫 {@code createId}，靠 {@code @TableField} 映射）</li>
 *   <li>{@code saveHand} 写入 {@code game_rounds} + 4 条 {@code game_round_scores}</li>
 *   <li><b>JSON 列合法</b>：{@code win_hand} / {@code fan_info} / {@code detail} 用
 *       SQL 的 {@code JSON_VALID()} 断言 —— 这是"绕过 Map 直传字符串"这个决定的验收点</li>
 *   <li><b>幂等</b>：同一 (session_id, round_num) 存两次不报唯一键冲突、流水不翻倍</li>
 * </ol>
 *
 * <p><b>需要虚拟机在跑</b>（MySQL 192.168.133.128 + Redis）。凭据从 {@code .env} 注入环境变量。
 * 类级 {@code @Transactional} 让每个用例结束后回滚，<b>不会污染库</b>
 * （唯一的例外是 {@code initSchema} 的建表 —— 那是应用启动时提交的，本来就该留下）。</p>
 */
@SpringBootTest
@Transactional
class RoundPersistenceServiceIT {

    @Autowired
    private RoundPersister persist;

    @Autowired
    private GameSessionsMapper sessions;

    @Autowired
    private GameRoundsMapper rounds;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 造一个 6 位房间码，避免与真实数据撞车（虽然事务会回滚，但这样更清晰）。 */
    private static String testRoomCode() {
        return "IT" + (System.currentTimeMillis() % 10000);
    }

    private List<GameRoundsMapper.ScoreRow> fourScores(long s0, long s1) {
        List<GameRoundsMapper.ScoreRow> rows = new ArrayList<>();
        // 自摸：赢家 +6，其余各 -2
        rows.add(new GameRoundsMapper.ScoreRow(s0, 0, 6, true, "{\"notes\":[],\"flowers\":[],\"gangs\":[]}"));
        rows.add(new GameRoundsMapper.ScoreRow(s1, 1, -2, false, "{\"notes\":[],\"flowers\":[],\"gangs\":[]}"));
        rows.add(new GameRoundsMapper.ScoreRow(-1L, 2, -2, false, "{\"notes\":[],\"flowers\":[],\"gangs\":[]}"));
        rows.add(new GameRoundsMapper.ScoreRow(-2L, 3, -2, false, "{\"notes\":[],\"flowers\":[],\"gangs\":[]}"));
        return rows;
    }

    @Test
    @DisplayName("createSession 写入成功，creator_id 列名对得上")
    void createSessionWritesCreatorId() {
        String code = testRoomCode();
        long sid = persist.createSession(code, 12345L, Arrays.asList(12345L, 0L, -1L, -2L));

        assertTrue(sid > 0, "应返回自增 session id，实际 " + sid);

        // 直接查列名 creator_id —— 若实体没映射对，这里会是 null（或 insert 时直接报错）
        Long creatorId = jdbc.queryForObject(
                "SELECT creator_id FROM game_sessions WHERE id = ?", Long.class, sid);
        assertEquals(12345L, creatorId, "creator_id 应为 12345（列名映射正确）");

        // 确认没有被写进一个不存在的 create_id 列（那会直接抛 SQL 异常，这里顺带断言 room_id）
        String roomId = jdbc.queryForObject(
                "SELECT room_id FROM game_sessions WHERE id = ?", String.class, sid);
        assertEquals(code, roomId);
    }

    @Test
    @DisplayName("saveHand 写 round + 4 条流水，且三个 JSON 列都合法")
    void saveHandWritesJsonColumns() {
        String code = testRoomCode();
        long sid = persist.createSession(code, 1L, Arrays.asList(1L, 2L, 0L, -1L));

        String winHand = "{\"hand\":[0,1,2],\"melds\":[{\"type\":\"PENG\",\"tiles\":[9,9,9]}]}";
        String fanInfo = "{\"PING_HU\":1}";

        long roundId = persist.saveHand(sid, 1, false, 0,
                1L, null, 5, winHand, fanInfo, 1, "EAST", 0, fourScores(1L, 2L));

        assertTrue(roundId > 0, "应返回自增 round id，实际 " + roundId);

        // ① round 写进去了
        Integer roundCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_rounds WHERE id = ?", Integer.class, roundId);
        assertEquals(1, roundCnt);

        // ② 三个 JSON 列必须合法 —— 这是"JSON 直传字符串"的验收点
        Boolean jsonOk = jdbc.queryForObject(
                "SELECT JSON_VALID(win_hand) AND JSON_VALID(fan_info) FROM game_rounds WHERE id = ?",
                Boolean.class, roundId);
        assertEquals(Boolean.TRUE, jsonOk, "win_hand / fan_info 必须是合法 JSON（不能是 {a=1} 这种）");

        Boolean detailOk = jdbc.queryForObject(
                "SELECT MIN(JSON_VALID(detail)) FROM game_round_scores WHERE round_id = ?",
                Boolean.class, roundId);
        assertEquals(Boolean.TRUE, detailOk, "detail 必须是合法 JSON");

        // ③ 四家流水都在，且总分守恒（+6 -2 -2 -2 = 0）
        Integer scoreCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_round_scores WHERE round_id = ?", Integer.class, roundId);
        assertEquals(4, scoreCnt, "应有 4 条流水");

        Integer sum = jdbc.queryForObject(
                "SELECT SUM(score_change) FROM game_round_scores WHERE round_id = ?", Integer.class, roundId);
        assertEquals(0, sum, "四家分数之和应为 0");

        // ④ game_sessions.current_round 被推进
        Long cur = jdbc.queryForObject(
                "SELECT current_round FROM game_sessions WHERE id = ?", Long.class, sid);
        assertEquals(1L, cur);
    }

    @Test
    @DisplayName("saveHand 幂等：同一把存两次不撞唯一键、流水不翻倍")
    void saveHandIsIdempotent() {
        String code = testRoomCode();
        long sid = persist.createSession(code, 1L, Arrays.asList(1L, 2L, 0L, -1L));
        String fanInfo = "{\"PING_HU\":1}";

        long first = persist.saveHand(sid, 1, false, 0, 1L, null, 5,
                "{\"hand\":[0],\"melds\":[]}", fanInfo, 1, "EAST", 0, fourScores(1L, 2L));
        // 重放同一把（服务重启后续跑会走到这里）
        long second = persist.saveHand(sid, 1, false, 0, 1L, null, 5,
                "{\"hand\":[0],\"melds\":[]}", fanInfo, 1, "EAST", 0, fourScores(1L, 2L));

        assertEquals(first, second, "重放应复用同一个 round，而不是新建");

        Integer roundCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_rounds WHERE session_id = ? AND round_num = 1", Integer.class, sid);
        assertEquals(1, roundCnt, "同一 (session_id, round_num) 只应有一条 round");

        Integer scoreCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_round_scores WHERE round_id = ?", Integer.class, first);
        assertEquals(4, scoreCnt, "重放后流水应仍是 4 条（先清后写），不能翻倍");
    }

    @Test
    @DisplayName("UserMapper 查询：selectUsername / selectNickname")
    void userMapperQueries() {
        // 用库里的第一个真实用户（如果有）
        Long uid = jdbc.query(
                "SELECT id FROM `user` ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);

        if (uid == null) {
            // 空库时至少验证"查不到返回 null"而不是抛异常
            assertEquals(null, userMapper.selectUsername(999999L));
            assertEquals(null, userMapper.selectNickname(999999L));
            return;
        }

        // 存在的用户：username 不应为 null（NOT NULL 列）
        assertNotNull(userMapper.selectUsername(uid), "已存在的用户 username 不应为 null");
        // 不存在的用户：返回 null
        assertEquals(null, userMapper.selectUsername(999999L));
    }
}
