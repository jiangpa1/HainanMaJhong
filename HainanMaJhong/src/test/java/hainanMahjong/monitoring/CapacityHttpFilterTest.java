package hainanMahjong.monitoring;

import hainanMahjong.room.RoomRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CapacityHttpFilterTest {
    @Test void streamsLargeUtf8BodyUnchangedAndCountsBusinessErrorWithHttp200() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CapacityHttpFilter filter = new CapacityHttpFilter(new CapacityMetrics(meters, new RoomRegistry()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/records/123");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/records/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = "{\"code\":401,\"message\":\"" + "中文".repeat(10000) + "\"}";
        filter.doFilter(request, response, (req, res) -> {
            res.setCharacterEncoding("UTF-8"); ((HttpServletResponse)res).getWriter().write(body);
        });
        assertEquals(body, response.getContentAsString()); assertEquals(200, response.getStatus());
        assertNotNull(response.getHeader("X-Request-ID"));
        assertEquals(1, meters.get("mahjong.http.response").tags("route", "/api/records/{id}", "outcome", "business_error").timer().count());
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, meters.get("mahjong.http.response.body").summary().totalAmount());
    }
    @Test void propagatesFailureAndRecordsServerError() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CapacityHttpFilter filter = new CapacityHttpFilter(new CapacityMetrics(meters, new RoomRegistry()));
        assertThrows(IllegalStateException.class, () -> filter.doFilter(new MockHttpServletRequest("GET", "/api/x"),
            new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("failed"); }));
        assertEquals(1, meters.get("mahjong.http.response").tag("outcome", "server_error").timer().count());
    }
}
