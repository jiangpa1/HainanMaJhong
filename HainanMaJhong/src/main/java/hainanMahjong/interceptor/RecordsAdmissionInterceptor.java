package hainanMahjong.interceptor;

import hainanMahjong.service.RequestAdmissionGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Only synchronous records GET controllers; registered after identity/authorization checks. */
@Component
public class RecordsAdmissionInterceptor implements HandlerInterceptor {
    private static final String PERMIT = RecordsAdmissionInterceptor.class.getName() + ".permit";
    private final RequestAdmissionGuard guard;

    public RecordsAdmissionInterceptor(RequestAdmissionGuard guard) { this.guard = guard; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equals(request.getMethod()) || request.getAttribute(PERMIT) != null) return true;
        Object user = request.getAttribute("userId");
        if (!(user instanceof Number id)) return true; // JWT interceptor owns unauthorized responses.
        request.setAttribute(PERMIT, guard.records(id.longValue(), request, false));
        return true;
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                           Object handler, Exception exception) {
        Object permit = request.getAttribute(PERMIT);
        if (permit instanceof RequestAdmissionGuard.Permit lease) lease.close();
    }
}
