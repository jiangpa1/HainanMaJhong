package hainanMahjong.monitoring;

import hainanMahjong.HainanMahjongApplication;
import hainanMahjong.service.impl.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HainanMahjongApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.profiles.active=monitor-test", "management.server.port=0", "capacity.external-probes-enabled=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/test", "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.hikari.initialization-fail-timeout=-1", "spring.datasource.hikari.minimum-idle=0",
        "spring.redis.host=127.0.0.1", "spring.redis.port=1", "jwt.secret=capacity-test-only-secret-32-characters-long",
        "jwt.access-expiration=30m", "jwt.refresh-expiration=7d", "jwt.issuer=test",
        "room-close-outbox.worker-enabled=false"})
@org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics
class CapacityManagementTest {
    @MockBean SchemaInitializer schema;
    @MockBean hainanMahjong.service.impl.RoomCloseOutbox outbox;
    @Autowired TestRestTemplate http;
    @LocalServerPort int appPort;
    @LocalManagementPort int managementPort;
    @Test void exposesPrometheusOnlyOnSeparateManagementPortAndKeepsOtherEndpointsDisabled() {
        assertNotEquals(appPort, managementPort);
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("http://127.0.0.1:" + appPort + "/actuator/prometheus", String.class).getStatusCode());
        var denied = http.getForEntity("http://127.0.0.1:" + appPort + "/api/user/me", String.class);
        assertTrue(denied.getBody().contains("401"));
        var recordsDenied = http.getForEntity("http://127.0.0.1:" + appPort + "/api/v2/records/42/rounds", String.class);
        assertTrue(recordsDenied.getBody().contains("401"));
        var metrics = http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, metrics.getStatusCode());
        assertTrue(metrics.getBody().contains("mahjong_ws_send_pending"));
        assertTrue(metrics.getBody().contains("jvm_memory_used_bytes"));
        assertTrue(metrics.getBody().contains("mahjong_http_response_seconds_bucket"));
        assertTrue(metrics.getBody().contains("mahjong_http_response_body_bytes_sum"));
        assertTrue(metrics.getBody().contains("outcome=\"business_error\""));
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/env", String.class).getStatusCode());
    }
}
