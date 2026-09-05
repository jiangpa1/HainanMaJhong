package hainanjong.service;

import hainanjong.FanType;
import hainanjong.HuLib;
import hainanjong.game.RoundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * MySQL 服务：持久化牌局记录。
 *
 * <p>启动时自动建表（幂等），一局结束后写入一条 {@code game_record}。</p>
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
            jdbc.execute("CREATE TABLE IF NOT EXISTS game_record ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " room_id VARCHAR(64) NOT NULL,"
                    + " result VARCHAR(16) NOT NULL,"
                    + " winner_seat VARCHAR(16),"
                    + " win_type VARCHAR(16),"
                    + " fan_types VARCHAR(128),"
                    + " win_tile VARCHAR(16),"
                    + " flower_count INT DEFAULT 0,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            log.info("game_record 表已就绪");
        } catch (Exception e) {
            log.warn("初始化 game_record 表失败（请确认 MySQL 已启动）：{}", e.getMessage());
        }
    }

    /** 写入一条牌局记录（胡牌或流局）。 */
    public void saveGameRecord(String roomId, RoundResult r) {
        String result = r.isDraw ? "DRAW" : "WIN";
        String winner = r.winner == null ? null : r.winner.name();
        String winType = r.isDraw ? null : (r.selfDraw ? "SELF_DRAW" : "DISCARD");
        String fans = r.hu == null ? null : joinFans(r.hu.fanTypes);
        String tile = r.winTile >= 0 ? HuLib.cardName(r.winTile) : null;
        int flowers = r.hu == null ? 0 : r.hu.flowerCount;

        jdbc.update("INSERT INTO game_record"
                        + " (room_id, result, winner_seat, win_type, fan_types, win_tile, flower_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                roomId, result, winner, winType, fans, tile, flowers);
        log.info("已写入牌局记录：room={}, result={}", roomId, result);
    }

    /** 最近若干局战绩。 */
    public List<Map<String, Object>> listGameRecords(int limit) {
        return jdbc.queryForList(
                "SELECT id, room_id, result, winner_seat, win_type, fan_types, win_tile, flower_count, create_time"
                        + " FROM game_record ORDER BY id DESC LIMIT " + limit);
    }

    private String joinFans(List<FanType> fans) {
        if (fans == null || fans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (FanType f : fans) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(f.toString());
        }
        return sb.toString();
    }
}
