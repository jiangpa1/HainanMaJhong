package hainanMahjong.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 服务：缓存进行中的房间状态（用于断线/重启恢复）。
 *
 * <p>{@code room:{roomId}} 保存带 generation/version 的 JSON，30 分钟过期；
 * {@code room:lifecycle:{roomId}} 保存代际、版本、会话和关闭状态，24 小时过期。
 * 保护记录不存在时保存拒绝，防止超迟任务在关闭保护过期后复活房间。</p>
 */
@Service
public class RoomSnapshotRepository {

    private static final String ROOM_PREFIX = "room:"; // room:{roomId} -> {version, data}

    private final StringRedisTemplate redis;

    public RoomSnapshotRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 保存不能创建保护记录：保护过期后，旧任务也不能重新激活房间。
    private static final String TTL = "86400000";
    private static final DefaultRedisScript<Long> BEGIN = script(
            "if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('HGET', KEYS[2], 'closed') == '0' then return 0 end "
            + "redis.call('HSET', KEYS[2], 'generation', ARGV[1], 'version', '0', 'session', '-1', 'closed', '0'); "
            + "redis.call('PEXPIRE', KEYS[2], ARGV[2]); return 1");
    private static final DefaultRedisScript<Long> SAVE = script(
            "if redis.call('HGET', KEYS[2], 'generation') ~= ARGV[1] or redis.call('HGET', KEYS[2], 'closed') ~= '0' then return 0 end "
            + "local v = tonumber(redis.call('HGET', KEYS[2], 'version')); if tonumber(ARGV[2]) <= v then return 0 end "
            + "local s = redis.call('HGET', KEYS[2], 'session'); if s ~= '-1' and s ~= ARGV[3] then return 0 end "
            + "redis.call('HSET', KEYS[2], 'version', ARGV[2], 'session', ARGV[3]); "
            + "redis.call('PEXPIRE', KEYS[2], ARGV[5]); redis.call('SET', KEYS[1], ARGV[4], 'PX', 1800000); return 1");
    private static final DefaultRedisScript<Long> CLOSE = script(
            "local g = redis.call('HGET', KEYS[2], 'generation'); if g and g ~= ARGV[1] then return 0 end "
            + "redis.call('HSET', KEYS[2], 'generation', ARGV[1], 'closed', '1'); "
            + "redis.call('PEXPIRE', KEYS[2], ARGV[2]); redis.call('DEL', KEYS[1]); return 1");
    private static final DefaultRedisScript<Long> RESTORE = script(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end "
            + "local g = redis.call('HGET', KEYS[2], 'generation'); "
            + "if g then if g ~= ARGV[2] or redis.call('HGET', KEYS[2], 'closed') ~= '0' "
            + "or redis.call('HGET', KEYS[2], 'version') ~= ARGV[3] "
            + "or redis.call('HGET', KEYS[2], 'session') ~= ARGV[4] then return 0 end "
            + "else redis.call('HSET', KEYS[2], 'generation', ARGV[2], 'version', ARGV[3], 'session', ARGV[4], 'closed', '0') end "
            + "redis.call('PEXPIRE', KEYS[2], ARGV[5]); return 1");
    private static DefaultRedisScript<Long> script(String lua) { return new DefaultRedisScript<>(lua, Long.class); }
    private boolean execute(DefaultRedisScript<Long> script, String code, String... args) {
        return Long.valueOf(1).equals(redis.execute(script, Arrays.asList(ROOM_PREFIX + code, "room:lifecycle:" + code), (Object[]) args));
    }
    public boolean beginRoom(String code, String generation) { return execute(BEGIN, code, generation, TTL); }
    public boolean saveRoomState(String code, String generation, long version, long sessionId, String json) {
        return execute(SAVE, code, generation, Long.toString(version), Long.toString(sessionId), json, TTL);
    }
    public boolean closeRoom(String code, String generation) { return execute(CLOSE, code, generation, TTL); }
    /** 以完整 JSON 比较后认领恢复；旧格式也必须经过业务合法性检查。 */
    public boolean claimRestore(String code, String json, String generation, long version, long sessionId) {
        return execute(RESTORE, code, json, generation, Long.toString(version), Long.toString(sessionId), TTL);
    }

    /** 读取某个房间进行中的状态；没有则返回 null。 */
    public String getRoomState(String roomId) {
        return redis.opsForValue().get(ROOM_PREFIX + roomId);
    }

}
