package hainanjong.service;

import hainanjong.game.Seat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 服务：缓存实时牌局状态、玩家在线状态、最近战绩。
 *
 * <p>所有 key 都带 {@code mahjong:} 前缀，方便区分与清理。</p>
 */
@Service
public class RedisService {

    private static final String ONLINE_KEY = "mahjong:online";
    private static final String ROOM_PREFIX = "mahjong:room:";
    private static final String LATEST_PREFIX = "mahjong:latest:";

    private final StringRedisTemplate redis;

    public RedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 玩家在线 ====================

    public void markOnline(Seat seat) {
        redis.opsForSet().add(ONLINE_KEY, seat.name());
    }

    public void markOffline(Seat seat) {
        redis.opsForSet().remove(ONLINE_KEY, seat.name());
    }

    public boolean isOnline(Seat seat) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(ONLINE_KEY, seat.name()));
    }

    // ==================== 牌局状态（Hash） ====================

    public void putRoomState(String roomId, String field, String value) {
        redis.opsForHash().put(ROOM_PREFIX + roomId, field, value);
    }

    public Map<Object, Object> getRoomState(String roomId) {
        return redis.opsForHash().entries(ROOM_PREFIX + roomId);
    }

    // ==================== 最近战绩（带过期） ====================

    public void cacheLatestResult(String roomId, String summary) {
        redis.opsForValue().set(LATEST_PREFIX + roomId, summary, Duration.ofMinutes(30));
    }

    public String getLatestResult(String roomId) {
        return redis.opsForValue().get(LATEST_PREFIX + roomId);
    }
}
