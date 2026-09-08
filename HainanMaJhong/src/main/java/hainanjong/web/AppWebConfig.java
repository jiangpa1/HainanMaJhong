package hainanjong.web;

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
public class AppWebConfig implements WebMvcConfigurer {

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
    }
}
