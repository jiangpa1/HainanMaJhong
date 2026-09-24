package hainanMahjong.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 表结构初始化：启动时幂等建表 + 给存量库补列/补索引。
 *
 * <p><b>本类现在只干这一件事</b> —— 原来的读写方法（用户查询、对局落库）都已经迁到
 * MyBatis Mapper：</p>
 *
 * <ul>
 *   <li>用户查询 → {@link hainanMahjong.mapper.UserMapper#selectUsername}/{@code selectNickname}</li>
 *   <li>建场/解散/存一把 → {@link hainanMahjong.mapper.GameSessionsMapper} +
 *       {@link hainanMahjong.mapper.GameRoundsMapper}，
 *       事务边界在 {@link RoundPersister#saveHand}</li>
 * </ul>
 *
 * <p><b>为什么 DDL 不用 Mapper</b>：建表/改列/补索引是"启动期一次性"的运维动作，
 * 不是业务查询；用 {@code JdbcTemplate.execute} 直连最直接，也不受 MyBatis 映射规则约束。
 * 而且它必须容忍失败（见下）。</p>
 */
@Service
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建表 + 兼容旧库升级。
     *
     * <p>整体 {@code try/catch}：这里跑在 {@code @PostConstruct}，抛异常会导致<b>应用起不来</b>；
     * 而建表失败通常只是"数据库没启动"或"没权限"，此时让应用带着空库起来、给出告警，
     * 比直接崩掉更容易排查。</p>
     */
    @PostConstruct
    public void initSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS `user` ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " username VARCHAR(32) NOT NULL UNIQUE,"
                    + " password VARCHAR(100) NOT NULL,"
                    + " nickname VARCHAR(32),"
                    + " role TINYINT NOT NULL DEFAULT 0,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 兼容旧表：补加 nickname 列
            try {
                jdbc.execute("ALTER TABLE `user` ADD COLUMN nickname VARCHAR(32)");
            } catch (Exception ignored) {
                // 列已存在，忽略
            }
            // 兼容旧表：补加 role 列。
            // ⚠️ 这一列是【必需】的，不是可选优化：User 实体有 role 字段，
            // MyBatis-Plus 生成的 SELECT / INSERT 都会带上 role，
            // 缺列会让【登录和注册直接 500】：Unknown column 'role' in 'field list'。
            try {
                jdbc.execute("ALTER TABLE `user` ADD COLUMN role TINYINT NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
                // 列已存在，忽略
            }
            // 兼容旧表：password 列放宽到 100，为 BCrypt（60 字符）留出余量，
            // 也避免以后换 Argon2（编码后约 95 字符）时超长。
            // 改不动就忽略 —— 这里是 @PostConstruct，抛异常会导致应用起不来；
            // 而 60 字符塞进 VARCHAR(64) 本身不会出错，功能不受影响。
            try {
                jdbc.execute("ALTER TABLE `user` MODIFY COLUMN password VARCHAR(100) NOT NULL");
            } catch (Exception ignored) {
                // 无权限或列已是目标宽度，忽略
            }
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_sessions ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " room_id VARCHAR(20) NOT NULL,"
                    + " creator_id BIGINT,"
                    + " player_ids JSON,"
                    + " current_round INT DEFAULT 1,"
                    + " status TINYINT DEFAULT 0,"
                    + " start_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " end_time DATETIME,"
                    + " KEY idx_room (room_id)"
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
                    + " dealer VARCHAR(8),"
                    + " wind TINYINT,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " UNIQUE KEY uk_session_round (session_id, round_num)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_round_scores ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " round_id BIGINT NOT NULL,"
                    + " user_id BIGINT NOT NULL,"
                    + " seat TINYINT NOT NULL,"
                    + " score_change INT DEFAULT 0,"
                    + " is_winner BOOLEAN DEFAULT 0,"
                    + " detail JSON,"
                    + " UNIQUE KEY uk_round_user (round_id, user_id),"
                    + " KEY idx_user_round (user_id, round_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 兼容旧表：补加 detail 列
            try {
                jdbc.execute("ALTER TABLE game_round_scores ADD COLUMN detail JSON");
            } catch (Exception ignored) {
                // 列已存在，忽略
            }
            // 存量库补索引/唯一键（新建库已由上面的 CREATE 内联带出，这里跳过）
            ensureIndex("game_sessions", "idx_room", false, "room_id");
            ensureIndex("game_rounds", "uk_session_round", true, "session_id", "round_num");
            ensureIndex("game_round_scores", "uk_round_user", true, "round_id", "user_id");
            ensureIndex("game_round_scores", "idx_user_round", false, "user_id", "round_id");
            // 兼容旧表：补加 庄/令 列
            try {
                jdbc.execute("ALTER TABLE game_rounds ADD COLUMN dealer VARCHAR(8)");
            } catch (Exception ignored) {
            }
            try {
                jdbc.execute("ALTER TABLE game_rounds ADD COLUMN wind TINYINT");
            } catch (Exception ignored) {
            }
            log.info("数据表已就绪：user / game_sessions / game_rounds / game_round_scores");
        } catch (Exception e) {
            log.warn("初始化数据表失败（请确认 MySQL 已启动）：{}", e.getMessage());
        }
    }

    /** 幂等补索引：目标索引不存在时才 ALTER 添加，单次失败不阻断后续（兼容旧库升级）。 */
    private void ensureIndex(String table, String index, boolean unique, String... cols) {
        try {
            List<Integer> rows = jdbc.query(
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    (rs, i) -> rs.getInt(1), table, index);
            if (!rows.isEmpty() && rows.get(0) > 0) {
                return;
            }
            jdbc.execute("ALTER TABLE `" + table + "` ADD "
                    + (unique ? "UNIQUE KEY `" : "KEY `") + index + "` (" + String.join(", ", cols) + ")");
            log.info("数据表 {} 已补索引 {}", table, index);
        } catch (Exception e) {
            log.warn("为 {} 补索引 {} 失败（可忽略）：{}", table, index, e.getMessage());
        }
    }
}
