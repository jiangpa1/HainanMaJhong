package hainanMahjong.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.common.Result;
import hainanMahjong.service.TokenService;
import hainanMahjong.service.impl.MultiPlayerRoomServiceImpl;
import hainanMahjong.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器。
 *
 * <p><b>它防的是什么</b>：原来 /room、/game 的身份完全来自 URL 上的
 * {@code ?userId=123}。这意味着任何人只要知道别人的 userId，就能连上去冒充他——
 * 直接把对手的牌打完、把房主踢掉都做得到。userId 是自己注册出来的连续数字，
 * 猜都不用猜。这就是典型的越权（IDOR），而且没有密码、没有签名，无从校验。</p>
 *
 * <p><b>怎么防</b>：在 HTTP 升级成 WebSocket 之前（此时还是普通 HTTP 请求，
 * 能拿到请求头），用和 JwtInterceptor 完全相同的规则校验 accessToken。
 * 通过就把 userId / username / role 塞进 WebSocketSession 的属性里，
 * 之后所有业务代码只认这份属性，不再解析 URL 上的 userId。</p>
 *
 * <p><b>token 从哪来</b>：优先看 {@code Authorization: Bearer &lt;token&gt;} 请求头，
 * 其次看 {@code ?token=} 查询参数。浏览器原生 WebSocket API 不允许自定义请求头，
 * 所以前端用查询参数这条兜底通道；但保留请求头是有意义的——
 * 将来换成 SockJS/STOMP 或原生客户端（Android/Unity）时可以直接走标准头。</p>
 *
 * <p><b>注意 token 放在 URL 里会被 nginx 记进 access.log</b>，属于已知取舍；
 * 本地部署可接受，上了公网应改走 Cookie 或一次性 ticket。</p>
 */
@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsAuthHandshakeInterceptor.class);

    private static final String HEADER_NAME = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * 握手成功后写进 WebSocketSession 的属性名。
     *
     * <p>userId 这个 key 由业务侧 {@code MultiPlayerRoomServiceImpl.ATTR_USER_ID} 定义，
     * 这里引它而不是自己再写一遍字面量——两边任何一处改错都会变成"能连上但认不出身份"，
     * 是最难查的一类 bug。</p>
     */
    public static final String ATTR_USER_ID = MultiPlayerRoomServiceImpl.ATTR_USER_ID;
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLE = "role";

    private final JwtUtils jwtUtils;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public WsAuthHandshakeInterceptor(JwtUtils jwtUtils,
                                      TokenService tokenService,
                                      ObjectMapper objectMapper) {
        this.jwtUtils = jwtUtils;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {

        String token = resolveToken(request);
        Claims claims = parse(token);

        if (claims == null) {
            reject(response, "未登录或登录已过期，请重新登录");
            return false;
        }

        // 单点登录：这台设备如果已经被别的设备顶掉，连握手都不给过。
        // 否则旧设备的牌局连接会一直重连成功，等于没顶掉。
        Long userId = jwtUtils.getUserId(claims);
        if (!tokenService.isSessionCurrent(userId, token)) {
            reject(response, "该账号在别处登录，请重新登录");
            return false;
        }

        // 身份只认 token 里的，URL 上的 userId 一律忽略（传了也不看，避免两处不一致）
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_USERNAME, claims.get("username", String.class));
        Number role = claims.get("role", Number.class);
        attributes.put(ATTR_ROLE, role == null ? null : role.intValue());

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手完成后无需处理
    }

    /**
     * 校验并解析 accessToken。
     *
     * @return 校验通过返回 claims；任何一步不通过返回 {@code null}（调用方据此拒绝握手）
     */
    private Claims parse(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            Claims claims = jwtUtils.parseToken(token);

            // refreshToken 不能拿来连 WebSocket：它有效期 7 天，一旦泄漏影响面远大于 30 分钟的 accessToken
            if (!jwtUtils.isAccessToken(claims)) {
                return null;
            }
            // 已登出的 token 会被拉黑，这里必须查一次，否则退出登录后旧连接还能重连
            if (tokenService.isRevoked(token)) {
                return null;
            }
            return claims;

        } catch (ExpiredJwtException e) {
            log.debug("WebSocket 握手失败：token 已过期");
        } catch (SignatureException | MalformedJwtException e) {
            log.warn("WebSocket 握手失败：token 签名不合法或结构损坏");
        } catch (JwtException e) {
            // 兜底，避免任何 JWT 异常冒泡成 500
            log.warn("WebSocket 握手失败：{}", e.getMessage());
        }
        return null;
    }

    /** 先看 Authorization 头，再看 ?token= 查询参数。 */
    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HEADER_NAME);
        if (header != null) {
            String h = header.trim();
            if (h.length() >= PREFIX.length()
                    && h.substring(0, PREFIX.length()).equalsIgnoreCase(PREFIX)) {
                String t = h.substring(PREFIX.length()).trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        String t = queryParam(request, "token");
        return (t == null || t.isEmpty()) ? null : t;
    }

    /** 从 URI 查询串里取参数（握手阶段没有 Spring MVC 的 @RequestParam）。 */
    private String queryParam(ServerHttpRequest request, String name) {
        String q = request.getURI().getQuery();
        if (q == null) {
            return null;
        }
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 拒绝握手。
     *
     * <p>返回 401 而不是 200：浏览器对握手失败的 WebSocket 只会给出一个笼统的
     * error 事件，唯一能把「是没登录」和「是服务挂了」区分开的线索就是这个 HTTP 状态码。
     * （普通接口那边为了配合前端统一解包仍然返回 200，WebSocket 没有这层解包逻辑，
     * 所以这里保留真实状态码更有用。）</p>
     */
    private void reject(ServerHttpResponse response, String message) throws Exception {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = objectMapper.writeValueAsString(Result.unauthorized(message));
        response.getBody().write(body.getBytes(StandardCharsets.UTF_8));
        response.getBody().flush();
    }
}
