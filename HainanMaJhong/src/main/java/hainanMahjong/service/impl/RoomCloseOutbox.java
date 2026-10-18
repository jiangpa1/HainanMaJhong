package hainanMahjong.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.annotation.PostConstruct;
import java.util.List;

/** Single-instance durable Redis cleanup jobs, committed with the SQL session close. */
@Repository
public class RoomCloseOutbox {
    public static final String DDL = "CREATE TABLE IF NOT EXISTS room_close_outbox ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL, "
            + "room_code VARCHAR(20) NOT NULL, generation VARCHAR(64) NOT NULL, "
            + "status VARCHAR(8) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, "
            + "next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
            + "created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), completed_at DATETIME(3), "
            + "last_error VARCHAR(160), UNIQUE KEY uk_session (session_id), "
            + "KEY idx_due (status,next_attempt_at,id), KEY idx_retention (status,completed_at,id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    private final JdbcTemplate jdbc;

    public RoomCloseOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Fail startup explicitly rather than silently running without the promised durability.
    @PostConstruct public void initialize() { jdbc.execute(DDL); }

    public void enqueue(long sessionId, String code, String generation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Room close outbox requires the session transaction");
        if (sessionId <= 0 || code == null || code.length() > 20 || generation == null
                || generation.isBlank() || generation.length() > 64)
            throw new IllegalArgumentException("Invalid room close identity");
        // A conflicting identity must fail and roll back the close, never replace an old job.
        jdbc.update("INSERT INTO room_close_outbox(session_id,room_code,generation) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE session_id=session_id", sessionId, code, generation);
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM room_close_outbox "
                + "WHERE session_id=? AND room_code=? AND generation=?", Integer.class, sessionId, code, generation);
        if (matches == null || matches != 1) throw new IllegalStateException("Conflicting room close identity");
    }

    public List<Job> due() {
        return jdbc.query("SELECT id,session_id,room_code,generation,attempts FROM room_close_outbox "
                + "WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3) "
                + "ORDER BY next_attempt_at,id LIMIT 16", (rs, row) -> new Job(rs.getLong("id"),
                rs.getLong("session_id"), rs.getString("room_code"), rs.getString("generation"), rs.getInt("attempts")));
    }

    public void complete(long sessionId, String generation) {
        jdbc.update("UPDATE room_close_outbox SET status='DONE',completed_at=CURRENT_TIMESTAMP(3),last_error=NULL "
                + "WHERE session_id=? AND generation=? AND status='PENDING'", sessionId, generation);
    }

    public void fail(Job job, String errorType) {
        int attempt = job.attempts() + 1;
        int delaySeconds = Math.min(300, 1 << Math.min(attempt, 9));
        jdbc.update("UPDATE room_close_outbox SET attempts=attempts+1,status=?,last_error=?, "
                + "next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) "
                + "WHERE id=? AND status='PENDING' AND attempts=?", attempt >= 12 ? "DEAD" : "PENDING",
                errorType, delaySeconds, job.id(), job.attempts());
    }

    public Stats stats() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(status='PENDING'),0) pending, "
                + "COALESCE(SUM(status='DEAD'),0) dead, COALESCE(MAX(CASE WHEN status='PENDING' "
                + "THEN TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP) ELSE 0 END),0) oldest "
                + "FROM room_close_outbox WHERE status IN ('PENDING','DEAD')",
                (rs, row) -> new Stats(rs.getLong("pending"), rs.getLong("dead"), rs.getLong("oldest")));
    }

    public void prune() {
        jdbc.update("DELETE FROM room_close_outbox WHERE status='DONE' "
                + "AND completed_at<TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) LIMIT 100");
    }

    public record Job(long id, long sessionId, String code, String generation, int attempts) { }
    public record Stats(long pending, long dead, long oldestSeconds) { }
}
