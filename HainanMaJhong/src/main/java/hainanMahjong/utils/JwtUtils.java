package hainanMahjong.utils;

import hainanMahjong.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils {
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;
    private Key secretKey;
    public JwtUtils(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    //初始化密钥
    @PostConstruct
    public void init(){
        if (jwtProperties.getAccessExpiration() == null || jwtProperties.getRefreshExpiration() == null) {
            throw new IllegalStateException(
                    "缺少配置：jwt.access-expiration / jwt.refresh-expiration 都必须配置（如 30m、7d）");
        }
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    //动态读取配置生成Token
    public String generateAccessToken(Long userId, String username, Integer role) {
        return generateToken(userId, username, jwtProperties.getAccessExpiration(), TYPE_ACCESS, role);
    }

    public String generateRefreshToken(Long userId, String username, Integer role) {
        return generateToken(userId, username, jwtProperties.getRefreshExpiration(), TYPE_REFRESH, role);
    }

    private String generateToken(Long userId, String username, Duration ttl, String typ, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", userId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put(CLAIM_TYPE, typ);

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        Date exp = new Date(nowMillis + ttl.toMillis());

        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setClaims(claims)
                .setSubject(userId.toString())
                .setId(UUID.randomUUID().toString())
                .setIssuer(jwtProperties.getIssuer())
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    //解析Toke
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                // 也可以强制校验 issuer 是否为 learning，防止伪造其他系统签发的 Token
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    //校验token类型
    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    //取userId
    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    //剩余毫秒数
    public Long getRemainingMillis(Claims claims) {
        Date exp = claims.getExpiration();
        return exp == null? 0L : exp.getTime() - System.currentTimeMillis();
    }

    /** token 哈希 —— 当作 Redis key 用（缩短长度 + 不把可用凭证明文堆在 Redis） */
    public String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));      // ★ 见下面坑 2
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然支持 SHA-256，走到这里说明环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
