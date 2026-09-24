package hainanMahjong.service.impl;

import hainanMahjong.common.CacheKeys;
import hainanMahjong.exception.BusinessException;
import hainanMahjong.properties.JwtProperties;
import hainanMahjong.service.TokenService;
import hainanMahjong.utils.JwtUtils;
import hainanMahjong.vo.TokenPair;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TokenServiceImpl implements TokenService {
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 登出后写进"当前会话"那一格的哨兵值。
     *
     * <p>它不可能等于任何 token 的哈希，于是登出后所有旧 accessToken 都会
     * {@code isSessionCurrent == false} —— 这正是我们要的"登出即失效"，
     * 同时也避免了"把整格删掉 → 没有记录 → fail-open 放行"的漏洞。</p>
     */
    private static final String SESSION_LOGGED_OUT = "-";

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public TokenServiceImpl(JwtUtils jwtUtils, StringRedisTemplate stringRedisTemplate, JwtProperties jwtProperties) {
        this.jwtUtils = jwtUtils;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public TokenPair issue(Long userId, String username, Integer role) {
        String accessToken = jwtUtils.generateAccessToken(userId, username, role);
        String refreshToken = jwtUtils.generateRefreshToken(userId, username, role);

        try {
            stringRedisTemplate.opsForValue().set(CacheKeys.tokenRefresh(userId),
                    jwtUtils.hashToken(refreshToken), jwtProperties.getRefreshExpiration());
            /*
             * 单点登录：把"当前有效的 accessToken"也记下来（覆盖写）。
             * 同一账号在别的设备登录时，这一格被新 token 顶掉，
             * 旧 accessToken 立刻在 isSessionCurrent() 里判为过期 —— 不用等它自然到期（30 分钟）。
             * 刷新也走这里，所以同一台设备续期后依然是"当前会话"。
             */
            stringRedisTemplate.opsForValue().set(CacheKeys.tokenSession(userId),
                    jwtUtils.hashToken(accessToken), jwtProperties.getRefreshExpiration());
        } catch (Exception e) {
            log.warn("登录缓存不可写", e);
            throw new BusinessException(503, "服务暂时不可用");
        }

        TokenPair tokenPair = new TokenPair();
        tokenPair.setAccessToken(accessToken);
        tokenPair.setRefreshToken(refreshToken);
        tokenPair.setExpiresIn(jwtProperties.getAccessExpiration().toMillis());

        return tokenPair;
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtils.parseToken(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(401, "登录已过期，请重新登陆");
        }catch (SignatureException | MalformedJwtException e) {
            // SignatureException：签名对不上，token 被篡改或不是本系统签发的
            // MalformedJwtException：token 结构损坏，根本不是合法 JWT
            // 注意 SignatureException 要用 io.jsonwebtoken.security 包下的那个，
            // io.jsonwebtoken 包下有个同名类已被废弃，catch 错了会捕获不到
            throw new BusinessException(401, "token 无效");
        } catch (JwtException e) {
            // 兜底，接住其余所有 JWT 相关异常，避免漏网后变成 500
            throw new BusinessException(401, "token 无效");
        }

        if(!jwtUtils.isRefreshToken(claims)){
            throw new BusinessException(401, "token类型错误");
        }

        Long userId = jwtUtils.getUserId(claims);

        String stored;
        try {
            stored = stringRedisTemplate.opsForValue().get(CacheKeys.tokenRefresh(userId));
        } catch (Exception e) {
            log.warn("无法查询到refresh key", e);
            throw new BusinessException(503, "服务暂时不可用");
        }
        if(stored == null || !stored.equals(jwtUtils.hashToken(refreshToken))){
            throw new BusinessException(401, "登录已失效，请重新登陆");
        }

        Number role = claims.get("role", Number.class);
        return issue(userId, claims.get("username", String.class), role == null ? null : role.intValue());
    }

    @Override
    public void logout(String authorization) {
        String token = stripBearer(authorization);
        if(token == null) {
            log.warn("Authorization 头格式非法，登出未生效：{}", authorization);
            return;
        }

        Claims claims = parseQuietly(token);
        if(claims == null) return;

        Long userId = jwtUtils.getUserId(claims);
        try {
            stringRedisTemplate.delete(CacheKeys.tokenRefresh(userId));
            /*
             * 单点登录那一格：登出时【比较后再写入"已登出"标记】，而不是删掉它。
             *
             * 为什么不能删：删掉等于把"当前会话"抹成"没有记录"，而 isSessionCurrent
             * 对没有记录是放行的（fail-open，防 Redis 丢数据把全站踢下线）——
             * 于是"新设备登出"会顺手把"旧设备那把早该作废的 token"重新激活。
             * 写一个不可能与哈希相等的哨兵值，就能让所有旧 token 继续被拒。
             *
             * TTL 取 accessToken 的有效期：过了这段时间旧 accessToken 自己就过期了，
             * 这格留着也没意义。
             */
            String sessionKey = CacheKeys.tokenSession(userId);
            if (jwtUtils.isAccessToken(claims)
                    && jwtUtils.hashToken(token).equals(stringRedisTemplate.opsForValue().get(sessionKey))) {
                stringRedisTemplate.opsForValue().set(sessionKey, SESSION_LOGGED_OUT,
                        jwtProperties.getAccessExpiration());
            }
        } catch (Exception e) {
            log.warn("删除 refresh key 失败，不影响此次返回", e);
        }

        if (!jwtUtils.isAccessToken(claims)) {
            log.warn("登出时 token 类型不是 access，跳过黑名单");
            return;      // refresh key 已经删了，目的达到了
        }

        long remaining = jwtUtils.getRemainingMillis(claims);
        if (remaining > 0) {
            try {
                stringRedisTemplate.opsForValue().set(
                        CacheKeys.tokenBlacklist(jwtUtils.hashToken(token)), "1", remaining, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("写黑名单失败，登出未生效（fail-closed）", e);
                throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
            }
        }
    }

    private Claims parseQuietly(String token) {
        try {
            return jwtUtils.parseToken(token);
        }catch (ExpiredJwtException e){
            return e.getClaims();
        }catch (Exception e){
            log.warn("token 解析失败，登出未生效：{}", e.getMessage());
            return null;
        }
    }

    private String stripBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null; // 不是 Bearer 认证
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    @Override
    public boolean isRevoked(String accessToken) {
        String key = CacheKeys.tokenBlacklist(jwtUtils.hashToken(accessToken));
        try {
            return stringRedisTemplate.hasKey(key);
        }catch (Exception e){
            log.error("黑名单查询失败，按已吊销处理（fail-closed），key={}", key, e);
            throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 这个 accessToken 还是该账号【当前】的那一个吗（单点登录判定）。
     *
     * <p>三种情况放行（返回 true）：</p>
     * <ul>
     *   <li>Redis 里没有记录 —— 早期登录的账号、或 Redis 被清过（<b>fail-open</b>：
     *       不能因为缓存丢了就把所有人踢下线）；</li>
     *   <li>记录与手上的 token 哈希一致 —— 正常；</li>
     *   <li>查 Redis 出错 —— 同理 fail-open（真正要 fail-closed 的是上面的黑名单，
     *       那道关已经在拦截器里先跑过了，这里再 fail-closed 只是把 Redis 抖动放大成"全站掉线"）。</li>
     * </ul>
     *
     * <p>只有"记录存在且不是这一个"才算被顶掉。</p>
     */
    @Override
    public boolean isSessionCurrent(Long userId, String accessToken) {
        if (userId == null || accessToken == null || accessToken.isEmpty()) {
            return true;
        }
        String current;
        try {
            current = stringRedisTemplate.opsForValue().get(CacheKeys.tokenSession(userId));
        } catch (Exception e) {
            log.warn("单点登录状态查询失败，按放行处理（fail-open），userId={}", userId, e);
            return true;
        }
        if (current == null || current.isEmpty()) {
            return true;
        }
        // 登出哨兵：谁都不算"当前会话"（见 SESSION_LOGGED_OUT 的说明）
        if (SESSION_LOGGED_OUT.equals(current)) {
            return false;
        }
        return current.equals(jwtUtils.hashToken(accessToken));
    }
}
