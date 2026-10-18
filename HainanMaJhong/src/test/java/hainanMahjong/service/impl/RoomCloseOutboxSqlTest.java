package hainanMahjong.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC transactions on isolated H2/MySQL-mode, not a production MySQL certification. */
class RoomCloseOutboxSqlTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    RoomCloseOutbox outbox;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        outbox = new RoomCloseOutbox(jdbc); outbox.initialize();
        jdbc.execute("CREATE TABLE test_sessions(id BIGINT PRIMARY KEY,closed INT NOT NULL)");
        jdbc.update("INSERT INTO test_sessions VALUES(42,0)");
    }

    @Test void rollbackRemovesBothStateChangeAndEvent() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE test_sessions SET closed=1 WHERE id=42");
            outbox.enqueue(42, "123456", "g1");
            throw new IllegalStateException("crash before commit");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT closed FROM test_sessions WHERE id=42", Integer.class));
        assertTrue(outbox.due().isEmpty());
    }

    @Test void committedJobsSurviveRepositoryRecreationAndDuplicateEnqueue() {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE test_sessions SET closed=1 WHERE id=42");
            outbox.enqueue(42, "123456", "g1");
        });
        var restarted = new RoomCloseOutbox(jdbc); restarted.initialize();
        tx.executeWithoutResult(s -> restarted.enqueue(42, "123456", "g1"));
        assertEquals(1, restarted.due().size());
        assertThrows(IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> restarted.enqueue(42, "123456", "g2")));
        assertEquals("g1", restarted.due().get(0).generation());
        restarted.complete(42, "g2");
        assertEquals(1, restarted.due().size());
        restarted.complete(42, "g1"); restarted.complete(42, "g1");
        assertTrue(restarted.due().isEmpty());
    }

    @Test void retriesAreDelayedBoundedAndDeadJobsRetained() {
        tx.executeWithoutResult(s -> outbox.enqueue(42, "123456", "g1"));
        var job = outbox.due().get(0);
        outbox.fail(job, "TimeoutException");
        outbox.fail(job, "TimeoutException"); // stale snapshot must not double increment
        assertTrue(outbox.due().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM room_close_outbox", Integer.class));
        jdbc.update("UPDATE room_close_outbox SET attempts=11,next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        outbox.fail(outbox.due().get(0), "TimeoutException");
        assertTrue(outbox.due().isEmpty());
        assertEquals(1, outbox.stats().dead());
        outbox.prune();
        assertEquals(1, outbox.stats().dead());
    }

    @Test void batchesAndRetentionAreBounded() {
        tx.executeWithoutResult(s -> {
            for (int i=1; i<=120; i++) outbox.enqueue(i, "123456", "g"+i);
        });
        assertEquals(16, outbox.due().size());
        assertEquals(120, outbox.stats().pending());
        for (int i=1; i<=120; i++) outbox.complete(i, "g"+i);
        outbox.prune();
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(*) FROM room_close_outbox", Integer.class));
        jdbc.update("UPDATE room_close_outbox SET completed_at=TIMESTAMPADD(DAY,-2,CURRENT_TIMESTAMP)");
        outbox.prune();
        assertEquals(20, jdbc.queryForObject("SELECT COUNT(*) FROM room_close_outbox", Integer.class));
        outbox.prune();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM room_close_outbox", Integer.class));
    }
}
