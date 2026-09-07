package hainanjong.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis 服务：缓存进行中的房间状态（用于断线/重启恢复）。
 *
 * <p>存储结构：{@code room:{roomId} -> {version, data}}，30 分钟自动过期。</p>
 */
@Service
public class RedisService {

    private static final String ROOM_PREFIX = "room:"; // room:{roomId} -> {version, data}

    private final StringRedisTemplate redis;

    public RedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 保存某个房间进行中的状态（JSON 字符串含 version+data，覆盖写，30 分钟自动过期）。 */
    public void saveRoomState(String roomId, String json) {
        redis.opsForValue().set(ROOM_PREFIX + roomId, json, Duration.ofMinutes(30));
    }

    /** 读取某个房间进行中的状态；没有则返回 null。 */
    public String getRoomState(String roomId) {
        return redis.opsForValue().get(ROOM_PREFIX + roomId);
    }

    /** 对局结束后清除状态。 */
    public void deleteRoomState(String roomId) {
        redis.delete(ROOM_PREFIX + roomId);
    }
}
