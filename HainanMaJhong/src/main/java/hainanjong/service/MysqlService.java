package hainanjong.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 服务：用户表 + 对局三张表（game_sessions / game_rounds / game_round_scores）。
 *
 * <p>启动时自动建表（幂等）。</p>
 */
@Service
public class MysqlService {

    private static final Logger log = LoggerFactory.getLogger(MysqlService.class);

    private final JdbcTemplate jdbc;

    public MysqlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS `user` ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " username VARCHAR(32) NOT NULL UNIQUE,"
                    + " password VARCHAR(64) NOT NULL,"
                    + " nickname VARCHAR(32),"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 兼容旧表：补加 nickname 列
            try {
                jdbc.execute("ALTER TABLE `user` ADD COLUMN nickname VARCHAR(32)");
            } catch (Exception ignored) {
                // 列已存在，忽略
            }
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_sessions ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " room_id VARCHAR(20) NOT NULL,"
                    + " creator_id BIGINT,"
                    + " player_ids JSON,"
                    + " current_round INT DEFAULT 1,"
                    + " status TINYINT DEFAULT 0,"
                    + " start_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " end_time DATETIME"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_rounds ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " session_id BIGINT NOT NULL,"
                    + " round_num INT NOT NULL,"
                    + " is_draw BOOLEAN DEFAULT 0,"
                    + " win_type TINYINT DEFAULT 0,"
                    + " winner_id BIGINT,"
                    + " loser_id BIGINT,"
                    + " win_tile INT,"
                    + " win_hand JSON,"
                    + " fan_info JSON,"
                    + " total_fan INT DEFAULT 0,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_round_scores ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " round_id BIGINT NOT NULL,"
                    + " user_id BIGINT NOT NULL,"
                    + " seat TINYINT NOT NULL,"
                    + " score_change INT DEFAULT 0,"
                    + " is_winner BOOLEAN DEFAULT 0"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("数据表已就绪：user / game_sessions / game_rounds / game_round_scores");
        } catch (Exception e) {
            log.warn("初始化数据表失败（请确认 MySQL 已启动）：{}", e.getMessage());
        }
    }

    // ==================== 用户 ====================

    /** 注册用户；返回新用户 id，用户名已存在时返回 null。 */
    public Long registerUser(String username, String passwordHash) {
        try {
            long id = insertReturningKey("INSERT INTO `user`(username, password, nickname) VALUES(?, ?, ?)",
                    username, passwordHash, username);
            return id < 0 ? null : id;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 查询用户 id；不存在返回 null。 */
    public Long findUserId(String username) {
        List<Long> rows = jdbc.query("SELECT id FROM `user` WHERE username = ?",
                (rs, i) -> rs.getLong(1), username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询用户密码哈希；用户不存在返回 null。 */
    public String findPasswordHash(String username) {
        List<String> rows = jdbc.query("SELECT password FROM `user` WHERE username = ?",
                (rs, i) -> rs.getString(1), username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询用户名；机器人/游客（userId<=0）返回 null。 */
    public String getUsername(long userId) {
        if (userId <= 0) {
            return null;
        }
        List<String> rows = jdbc.query("SELECT username FROM `user` WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询昵称；未设置返回 null。 */
    public String findNickname(long userId) {
        if (userId <= 0) {
            return null;
        }
        List<String> rows = jdbc.query("SELECT nickname FROM `user` WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 id 查密码哈希；不存在返回 null。 */
    public String findPasswordHashById(long userId) {
        List<String> rows = jdbc.query("SELECT password FROM `user` WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 更新昵称。 */
    public void updateNickname(long userId, String nickname) {
        jdbc.update("UPDATE `user` SET nickname=? WHERE id=?", nickname, userId);
    }

    /** 更新密码哈希。 */
    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("UPDATE `user` SET password=? WHERE id=?", passwordHash, userId);
    }

    // ==================== 对局持久化（大对局 / 单局 / 流水） ====================

    /** 创建整场对局，返回 session id。 */
    public long createSession(String roomId, long creatorId, String playerIdsJson) {
        return insertReturningKey(
                "INSERT INTO game_sessions(room_id, creator_id, player_ids, current_round, status, start_time)"
                        + " VALUES(?,?,?,1,0,NOW())",
                roomId, creatorId, playerIdsJson);
    }

    /** 更新当前进行到第几把。 */
    public void updateSessionRound(long sessionId, int round) {
        jdbc.update("UPDATE game_sessions SET current_round=? WHERE id=?", round, sessionId);
    }

    /** 结束整场对局（正常打完 16 把）。 */
    public void finishSession(long sessionId) {
        jdbc.update("UPDATE game_sessions SET status=1, end_time=NOW() WHERE id=?", sessionId);
    }

    /** 玩家中途退出，标记为异常解散。 */
    public void disbandSession(long sessionId) {
        jdbc.update("UPDATE game_sessions SET status=2, end_time=NOW() WHERE id=?", sessionId);
    }

    /** 保存一把对局，返回 round id。winType：0 自摸 / 1 接炮 / 2 流局。 */
    public long saveRound(long sessionId, int roundNum, boolean isDraw, int winType,
                          Long winnerId, Long loserId, Integer winTile, String winHandJson,
                          String fanInfoJson, int totalFan) {
        return insertReturningKey(
                "INSERT INTO game_rounds(session_id, round_num, is_draw, win_type, winner_id, loser_id, win_tile, win_hand, fan_info, total_fan, create_time)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                sessionId, roundNum, isDraw ? 1 : 0, winType, winnerId, loserId, winTile, winHandJson, fanInfoJson, totalFan);
    }

    /** 保存一把对局中某位玩家的盈亏流水。 */
    public void saveRoundScore(long roundId, long userId, int seat, int scoreChange, boolean isWinner) {
        jdbc.update("INSERT INTO game_round_scores(round_id, user_id, seat, score_change, is_winner)"
                + " VALUES(?,?,?,?,?)", roundId, userId, seat, scoreChange, isWinner ? 1 : 0);
    }

    // ==================== 战绩查询 ====================

    /** 查询某房间的 room_id。 */
    public String getSessionRoomId(long sessionId) {
        List<String> rows = jdbc.query("SELECT room_id FROM game_sessions WHERE id = ?",
                (rs, i) -> rs.getString(1), sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 根据 room_id 反查 session id。 */
    public Long getSessionIdByRoomId(String roomId) {
        List<Long> rows = jdbc.query("SELECT id FROM game_sessions WHERE room_id = ?",
                (rs, i) -> rs.getLong(1), roomId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 用户参与过的所有房间（含房间基本信息），按时间倒序。 */
    public List<Map<String, Object>> listSessionsForUser(long userId) {
        return jdbc.queryForList(
                "SELECT s.id AS sessionId, s.room_id AS roomId, s.start_time AS startTime,"
                        + " s.end_time AS endTime, s.status AS status"
                        + " FROM game_sessions s"
                        + " WHERE EXISTS (SELECT 1 FROM game_rounds r JOIN game_round_scores rc ON rc.round_id = r.id"
                        + "   WHERE r.session_id = s.id AND rc.user_id = ?)"
                        + " ORDER BY s.id DESC", userId);
    }

    /** 某房间各玩家的最终总分与座位：userId -> [seat, totalScore]。 */
    public Map<Long, int[]> sessionPlayerTotals(long sessionId) {
        Map<Long, int[]> map = new HashMap<Long, int[]>();
        jdbc.query("SELECT rc.user_id, MAX(rc.seat), SUM(rc.score_change)"
                + " FROM game_round_scores rc JOIN game_rounds r ON r.id = rc.round_id"
                + " WHERE r.session_id = ? GROUP BY rc.user_id",
                rs -> {
                    map.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3)});
                }, sessionId);
        return map;
    }

    /** 某房间每一把的详情（含当前用户的积分变动），按局数排序。 */
    public List<Map<String, Object>> sessionRounds(long sessionId, long userId) {
        return jdbc.queryForList(
                "SELECT r.round_num AS roundNum, r.is_draw AS isDraw, r.win_type AS winType,"
                        + " r.winner_id AS winnerId, r.loser_id AS loserId, r.win_tile AS winTile,"
                        + " r.win_hand AS winHand, r.fan_info AS fanInfo, r.total_fan AS totalFan,"
                        + " rc.score_change AS scoreChange, rc.is_winner AS isWinner"
                        + " FROM game_rounds r"
                        + " LEFT JOIN game_round_scores rc ON rc.round_id = r.id AND rc.user_id = ?"
                        + " WHERE r.session_id = ? ORDER BY r.round_num",
                userId, sessionId);
    }

    private long insertReturningKey(String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }
}
