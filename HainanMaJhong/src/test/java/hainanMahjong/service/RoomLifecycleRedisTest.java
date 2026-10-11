package hainanMahjong.service;

import hainanMahjong.repository.RoomSnapshotRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用显式指定的本地隔离 Redis；不加载项目连接配置，不执行 FLUSHDB。 */
@EnabledIfSystemProperty(named = "lifecycle.redis.port", matches = "\\d+")
class RoomLifecycleRedisTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate template;
    private RoomSnapshotRepository repository;
    private String code;
    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory(System.getProperty("lifecycle.redis.host", "127.0.0.1"),
                Integer.getInteger("lifecycle.redis.port"));
        connection.afterPropertiesSet();
        template = new StringRedisTemplate(connection);
    }
    @AfterAll static void disconnect() { connection.destroy(); }
    @BeforeEach void setup() {
        code = "lifecycle-test:" + UUID.randomUUID(); repository = new RoomSnapshotRepository(template);
    }
    @AfterEach void clean() { template.delete(Arrays.asList("room:" + code, "room:lifecycle:" + code)); }

    @Test void newerSnapshotWinsAndSessionCannotChange() {
        assertTrue(repository.beginRoom(code, "a"));
        assertTrue(repository.saveRoomState(code, "a", 2, 42, "newer"));
        assertFalse(repository.saveRoomState(code, "a", 1, 42, "older"));
        assertFalse(repository.saveRoomState(code, "a", 2, 42, "same-version"));
        assertFalse(repository.saveRoomState(code, "a", 3, 99, "wrong-session"));
        assertEquals("newer", repository.getRoomState(code));
    }

    @Test void closeRejectsDelayedSaveEvenAfterGuardIsGone() {
        assertTrue(repository.beginRoom(code, "a"));
        assertTrue(repository.saveRoomState(code, "a", 1, 42, "snapshot"));
        assertTrue(repository.closeRoom(code, "a"));
        assertFalse(repository.saveRoomState(code, "a", 2, 42, "delayed"));
        template.delete("room:lifecycle:" + code); // 模拟保护 TTL 到期
        assertFalse(repository.saveRoomState(code, "a", 3, 42, "very-late"));
        assertNull(repository.getRoomState(code));
    }

    @Test void reusedCodeRejectsOldSaveAndOldClose() {
        assertTrue(repository.beginRoom(code, "a")); assertTrue(repository.closeRoom(code, "a"));
        assertTrue(repository.beginRoom(code, "b"));
        assertTrue(repository.saveRoomState(code, "b", 1, 99, "new-room"));
        assertFalse(repository.saveRoomState(code, "a", 2, 42, "old-room"));
        assertFalse(repository.closeRoom(code, "a"));
        assertEquals("new-room", repository.getRoomState(code));
    }

    @Test void restoreMustMatchCurrentSnapshotAndGuard() {
        assertTrue(repository.beginRoom(code, "a"));
        assertTrue(repository.saveRoomState(code, "a", 1, 42, "v1"));
        assertTrue(repository.claimRestore(code, "v1", "a", 1, 42));
        assertTrue(repository.saveRoomState(code, "a", 2, 42, "v2"));
        assertFalse(repository.claimRestore(code, "v1", "a", 1, 42));
        assertFalse(repository.claimRestore(code, "v2", "a", 2, 99));
        assertTrue(repository.closeRoom(code, "a"));
        assertFalse(repository.claimRestore(code, "v2", "a", 2, 42));
    }

    @Test void legacySnapshotCanBeClaimedButCannotBypassClose() {
        template.opsForValue().set("room:" + code, "legacy");
        assertFalse(repository.beginRoom(code, "new"));
        assertTrue(repository.claimRestore(code, "legacy", "legacy-generation", 0, 42));
        assertTrue(repository.saveRoomState(code, "legacy-generation", 1, 42, "migrated"));
        assertTrue(repository.closeRoom(code, "legacy-generation"));
        template.opsForValue().set("room:" + code, "legacy"); // 模拟旧版本程序的迟到写入
        assertFalse(repository.claimRestore(code, "legacy", "legacy-generation", 0, 42));
    }

    @Test void concurrentSaveAndCloseNeverLeaveSnapshot() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int n = 0; n < 30; n++) {
                String generation = "g" + n;
                assertTrue(repository.beginRoom(code, generation));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> save = workers.submit(() -> { await(start); repository.saveRoomState(code, generation, 1, 42, "snapshot"); });
                Future<?> close = workers.submit(() -> { await(start); assertTrue(repository.closeRoom(code, generation)); });
                start.countDown(); save.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS);
                assertNull(repository.getRoomState(code));
            }
        } finally { workers.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
