package hainanMahjong.websocket;

import hainanMahjong.properties.JwtProperties;
import hainanMahjong.service.TokenService;
import hainanMahjong.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 握手鉴权回归测试。
 *
 * <p>这组用例锁住的是一个越权漏洞的修复：修复前 {@code /room}、{@code /game} 的身份
 * 直接取 URL 上的 {@code ?userId=}，任何人写出别人的 userId 就能冒充对方。
 * 修复后身份只能来自握手时校验通过的 accessToken。</p>
 *
 * <p>握手期的两个参数是 Spring 的 {@code ServerHttpRequest/Response} 接口
 * （spring-web 的 servlet 版，不是 reactive 版），这里直接用 Mockito mock，
 * 不启动服务、不连数据库和 Redis。关键的两个真实行为（URI、请求头）用真对象喂进去。</p>
 */
class WsAuthHandshakeInterceptorTest {

    /** 必须 >= 32 字节，否则 JJWT 0.11 的 hmacShaKeyFor 会抛 WeakKeyException。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private JwtUtils jwtUtils;
    private TokenService tokenService;
    private WsAuthHandshakeInterceptor interceptor;
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessExpiration(Duration.ofMinutes(30));
        props.setRefreshExpiration(Duration.ofDays(7));

        jwtUtils = new JwtUtils(props);
        jwtUtils.init();                 // 等价于 Spring 的 @PostConstruct；不调则 secretKey 为 null

        tokenService = mock(TokenService.class);
        /*
         * 默认认为"这就是该账号当前的会话"。
         *
         * 必须显式打桩：Mockito 对 boolean 的默认返回是 false，而握手流程里
         * isSessionCurrent=false 表示"账号在别处登录了" → 一律拒绝握手。
         * 第一次加单点登录时漏了这行，三个"应当通过"的用例立刻红了。
         */
        when(tokenService.isSessionCurrent(any(), anyString())).thenReturn(true);
        interceptor = new WsAuthHandshakeInterceptor(jwtUtils, tokenService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        wsHandler = mock(WebSocketHandler.class);
    }

    // ==================== 辅助 ====================

    /** 造一个握手请求：uri 是完整的请求行 URI，headers 可以为空。 */
    private static ServerHttpRequest request(String uri, String authorization) {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(req.getURI()).thenReturn(URI.create(uri));
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        when(req.getHeaders()).thenReturn(headers);
        return req;
    }

    private static ServerHttpResponse response() throws Exception {
        ServerHttpResponse res = mock(ServerHttpResponse.class);
        when(res.getHeaders()).thenReturn(new HttpHeaders());
        when(res.getBody()).thenReturn(new ByteArrayOutputStream());
        return res;
    }

    /** 断言"拒绝握手"：返回 false、写了 401、且没往会话属性里塞任何身份。 */
    private static void assertRejected(boolean ok, ServerHttpResponse response,
                                       Map<String, Object> attributes) {
        assertFalse(ok);
        verify(response, atLeastOnce()).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertNull(attributes.get(WsAuthHandshakeInterceptor.ATTR_USER_ID));
    }

    // ==================== 通过 ====================

    @Test
    @DisplayName("token 放在 Authorization 头里：握手通过，身份写进会话属性")
    void acceptsTokenFromHeader() throws Exception {
        String token = jwtUtils.generateAccessToken(42L, "阿明", 0);
        ServerHttpRequest request = request("/room", "Bearer " + token);
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(ok);
        assertEquals(42L, attributes.get(WsAuthHandshakeInterceptor.ATTR_USER_ID));
        assertEquals("阿明", attributes.get(WsAuthHandshakeInterceptor.ATTR_USERNAME));
        assertEquals(0, attributes.get(WsAuthHandshakeInterceptor.ATTR_ROLE));
        verify(response, never()).setStatusCode(any());
    }

    @Test
    @DisplayName("token 放在 ?token= 查询参数里：握手通过（浏览器原生 WebSocket 不能加请求头）")
    void acceptsTokenFromQueryParam() throws Exception {
        String token = jwtUtils.generateAccessToken(7L, "bob", 1);
        ServerHttpRequest request = request("/game?code=AB12&seat=EAST&token=" + token, null);
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(ok);
        assertEquals(7L, attributes.get(WsAuthHandshakeInterceptor.ATTR_USER_ID));
        assertEquals(1, attributes.get(WsAuthHandshakeInterceptor.ATTR_ROLE));
    }

    @Test
    @DisplayName("握手成功时不做任何拒绝动作：不调 isRevoked 之外不该有副作用")
    void doesNotTouchResponseOnSuccess() throws Exception {
        String token = jwtUtils.generateAccessToken(11L, "ok", 0);
        ServerHttpResponse response = response();

        assertTrue(interceptor.beforeHandshake(
                request("/room", "bearer " + token),   // 大小写不敏感，客户端写 bearer 也应接受
                response, wsHandler, new LinkedHashMap<>()));

        verify(response, never()).setStatusCode(any());
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("只带 ?userId= 不带 token：拒绝握手（修复前这里能冒充任意用户）")
    void rejectsForgedUserIdWithoutToken() throws Exception {
        ServerHttpRequest request = request("/game?userId=999&code=AB12&seat=EAST", null);
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("完全没有任何凭证：拒绝握手")
    void rejectsBareRequest() throws Exception {
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request("/room", null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("Authorization 头前缀写了但 token 为空：拒绝握手")
    void rejectsEmptyBearerToken() throws Exception {
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room", "Bearer "), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("token 是伪造的（用别的密钥签名）：拒绝握手")
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        JwtProperties other = new JwtProperties();
        other.setSecret("ffffffffffffffffffffffffffffffffffffffffffffffff");
        other.setAccessExpiration(Duration.ofMinutes(30));
        other.setRefreshExpiration(Duration.ofDays(7));
        JwtUtils attacker = new JwtUtils(other);
        attacker.init();
        String forged = attacker.generateAccessToken(999L, "attacker", 1);

        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room?token=" + forged, null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("token 结构损坏（不是合法 JWT）：拒绝握手")
    void rejectsMalformedToken() throws Exception {
        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room?token=not-a-jwt-at-all", null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("token 已过期：拒绝握手")
    void rejectsExpiredToken() throws Exception {
        JwtProperties shortLived = new JwtProperties();
        shortLived.setSecret(SECRET);
        shortLived.setAccessExpiration(Duration.ofSeconds(-60));   // 签发时间已早于过期时间
        shortLived.setRefreshExpiration(Duration.ofDays(7));
        JwtUtils expiredIssuer = new JwtUtils(shortLived);
        expiredIssuer.init();
        String expired = expiredIssuer.generateAccessToken(5L, "old", 0);

        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room?token=" + expired, null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("已登出的 token（在黑名单里）：拒绝握手")
    void rejectsRevokedToken() throws Exception {
        String token = jwtUtils.generateAccessToken(8L, "gone", 0);
        when(tokenService.isRevoked(anyString())).thenReturn(true);

        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room?token=" + token, null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("拿 refreshToken 来连 WebSocket：拒绝（它有效期 7 天，不该能建长连接）")
    void rejectsRefreshToken() throws Exception {
        String refresh = jwtUtils.generateRefreshToken(3L, "carol", 0);

        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/room?token=" + refresh, null), response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
    }

    @Test
    @DisplayName("同一账号在别处登录后，旧设备的握手被拒（单点登录）")
    void rejectsStaleSession() throws Exception {
        String token = jwtUtils.generateAccessToken(42L, "阿明", 0);
        // 该账号的"当前会话"已经不是这一把 token 了
        when(tokenService.isSessionCurrent(any(), anyString())).thenReturn(false);

        ServerHttpResponse response = response();
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(
                request("/game?code=AB12&seat=EAST&token=" + token, null),
                response, wsHandler, attributes);

        assertRejected(ok, response, attributes);
        // 拒绝理由必须写清楚是"别处登录"，前端据此提示并回登录页
        String body = ((ByteArrayOutputStream) response.getBody()).toString("UTF-8");
        assertTrue(body.contains("别处登录"), "拒绝响应里应说明是别处登录，实际：" + body);
    }
}
