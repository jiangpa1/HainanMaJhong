package hainanMahjong.config;

import hainanMahjong.interceptor.AuthorizationInterceptor;
import hainanMahjong.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * APK(WebView) 缓存策略：HTML 页面一律不缓存，避免“服务端改了、App 还显示旧的”。
 * JS/CSS 等静态资源仍由引用里的版本号(如 landscape.js?v=…)控制缓存刷新。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, AuthorizationInterceptor authorizationInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
    }

    /** 不需要 accessToken 的接口：注册 / 登录 / 刷新。 */
    private static final String[] AUTH_FREE = {
            "/api/register",
            "/api/login",
            // 刷新的意义就是"accessToken 已经过期时用它换新的" ——
            // 那时客户端手里根本没有可用的 accessToken，所以必须放行，
            // 否则这个接口永远返回 401，形同不存在。
            "/api/refresh",
            // 排查"点了出牌没反应"用的只读诊断端点（DebugController）。
            // 放行是因为卡住时往往连 token 都可能是问题之一，
            // 要求先登录反而查不下去。它只读内存状态，不写库、不改对局。
            //
            // ⚠️ 它会把房间码 / 昵称 / userId 全吐出来，所以【只在 local profile 注册】
            //    （DebugController 上挂着 @Profile("local")）。云端用 prod profile 时
            //    这个端点不存在，这里放行一行也就成了死配置，不会造成暴露。
            "/api/debug/**"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String uri = request.getRequestURI();
                if ("/".equals(uri) || uri.endsWith(".html")) {
                    response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                    response.setHeader("Pragma", "no-cache");
                    response.setHeader("Expires", "0");
                }
                return true;
            }
        }).addPathPatterns("/", "/*.html");

        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(AUTH_FREE);

        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(AUTH_FREE);
    }
}
