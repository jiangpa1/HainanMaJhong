package hainanMahjong.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit opt-in; refuses any database except room_outbox_test. Inherits the SQL contract. */
@EnabledIfSystemProperty(named="outbox.mysql.url", matches="jdbc:mysql:.*")
class RoomCloseOutboxMySqlIT extends RoomCloseOutboxSqlTest {
    @Override @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getProperty("outbox.mysql.url"),
                System.getProperty("outbox.mysql.user", "root"),
                System.getenv().getOrDefault("OUTBOX_TEST_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(source);
        assertEquals("room_outbox_test", jdbc.queryForObject("SELECT DATABASE()", String.class),
                "Only the dedicated disposable test database is permitted");
        jdbc.execute("DROP TABLE IF EXISTS room_close_outbox");
        jdbc.execute("DROP TABLE IF EXISTS test_sessions");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        outbox = new RoomCloseOutbox(jdbc); outbox.initialize();
        jdbc.execute("CREATE TABLE test_sessions(id BIGINT PRIMARY KEY,closed INT NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO test_sessions VALUES(42,0)");
    }
}
