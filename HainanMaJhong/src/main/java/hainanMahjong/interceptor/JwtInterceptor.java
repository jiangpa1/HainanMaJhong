package hainanMahjong.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.common.Result;
import hainanMahjong.service.TokenService;
import hainanMahjong.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final String HEADER_NAME = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_USERNAME = "username";
    private static final String ATTR_ROLE = "role";

    private final JwtUtils jwtUtils;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtils jwtUtils, TokenService tokenService, ObjectMapper objectMapper) {
        this.jwtUtils = jwtUtils;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception{
        // ---------- 第 1 步：从请求头里取出 token ----------
        String header = request.getHeader(HEADER_NAME);

        // 完全没带这个头，直接拦下
        if (header == null || header.trim().isEmpty()) {
            writeUnauthorized(response, "未登录，请先登录");
            return false;
        }

        // ---------- 第 2 步：剥离 "Bearer " 前缀 ----------
        // 先判断长度够不够，再判断前缀内容，避免 substring 越界抛 StringIndexOutOfBoundsException
        // 用 equalsIgnoreCase 而不是 startsWith：Bearer 是 HTTP 认证方案名，
        // 按规范大小写不敏感，客户端写 bearer / BEARER 都应该接受
        if (header.length() < PREFIX.length()
                || !header.substring(0, PREFIX.length()).equalsIgnoreCase(PREFIX)) {
            writeUnauthorized(response, "token 格式错误");
            return false;
        }

        // 剥掉前缀，剩下的才是真正的 token；trim 去掉可能多出来的空格
        String token = header.substring(PREFIX.length()).trim();

        // 形如 "Bearer " 后面什么都没有，同样算格式错误
        if (token.isEmpty()) {
            writeUnauthorized(response, "token 格式错误");
            return false;
        }

        // ---------- 第 3 步：解析并校验 token ----------
        try {
            // parseToken 内部会校验签名和有效期，任何一项不通过都会抛异常
            Claims claims = jwtUtils.parseToken(token);

            if(!jwtUtils.isAccessToken(claims)){
                writeUnauthorized(response, "token类型错误");
                return false;
            }


            if(tokenService.isRevoked(token)){
                writeUnauthorized(response, "登录已失效，请重新登录");
                return false;
            }

            /*
             * 单点登录：同一账号在别的设备登录后，这台设备的 accessToken 立刻作废。
             * 用专门的业务码 4011 而不是普通 401 —— 前端要据此【直接退出到登录页并提示】，
             * 而不是像普通 401 那样先尝试用 refreshToken 续期（续期必然失败，白跑一趟）。
             */
            Long uid = jwtUtils.getUserId(claims);
            if (!tokenService.isSessionCurrent(uid, token)) {
                writeUnauthorized(response, Result.CODE_KICKED, "该账号在别处登录，请重新登录");
                return false;
            }

            // 解析成功，把用户身份挂到当前请求上，供后续 Controller 使用
            // 用 subject 取 userId：JwtUtils 里 setSubject(userId.toString()) 存的是字符串，
            // 这里再转回 Long，比直接从 claims 取数字字段更稳（JSON 数字可能被反序列化成 Integer）
            request.setAttribute(ATTR_USER_ID, uid);
            request.setAttribute(ATTR_USERNAME, claims.get("username", String.class));
            Number role = claims.get("role", Number.class);
            request.setAttribute(ATTR_ROLE, role == null ? null : role.intValue());

            // 返回 true，放行，继续走后面的 Controller
            return true;

        } catch (ExpiredJwtException e) {
            // 签名是对的，只是超过有效期了。单独区分出来，前端可以据此自动跳登录页
            writeUnauthorized(response, "登录已过期，请重新登录");

        } catch (SignatureException | MalformedJwtException e) {
            // SignatureException：签名对不上，token 被篡改或不是本系统签发的
            // MalformedJwtException：token 结构损坏，根本不是合法 JWT
            // 注意 SignatureException 要用 io.jsonwebtoken.security 包下的那个，
            // io.jsonwebtoken 包下有个同名类已被废弃，catch 错了会捕获不到
            writeUnauthorized(response, "token 无效");

        } catch (JwtException e) {
            // 兜底，接住其余所有 JWT 相关异常，避免漏网后变成 500
            writeUnauthorized(response, "token 无效");
        }

        // 只要走到这里就说明校验没通过，拦截
        return false;
    }

    /**
     * 往响应里写 401 和统一的 Result 结构。
     * 这是本类最关键的一处：preHandle 返回 false 只是告诉 Spring「别往下走了」，
     * 它不会帮你生成任何响应内容。如果不自己写，前端收到的是一个内容为空的 HTTP 200，
     * 既不是错误码也没有错误信息，排查起来非常费劲。
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        writeUnauthorized(response, hainanMahjong.common.Result.CODE_UNAUTHORIZED, message);
    }

    /** 同上，但指定业务码（单点登录用 4011，见 {@code Result.CODE_KICKED}）。 */
    private void writeUnauthorized(HttpServletResponse response, int code, String message) throws IOException {

        // HTTP 状态码设为 200
        response.setStatus(HttpServletResponse.SC_OK);

        // 必须带 charset=UTF-8，否则中文提示到前端会变乱码
        response.setContentType("application/json;charset=UTF-8");

        // 用注入的 ObjectMapper 序列化，不要手拼 JSON 字符串——字段名或转义出问题很难查
        response.getWriter().write(objectMapper.writeValueAsString(
                hainanMahjong.common.Result.build(code, message, null)));
    }


}

