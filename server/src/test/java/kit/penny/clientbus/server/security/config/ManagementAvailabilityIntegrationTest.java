package kit.penny.clientbus.server.security.config;

import kit.penny.clientbus.server.notification.outbox.UiOutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import java.net.http.HttpClient;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementAvailabilityIntegrationTest extends ManagementAuthenticationIntegrationSupport {
    @Autowired UiOutboxMetrics samples;
    @Autowired MeterRegistry meters;
    @Autowired JsonMapper mapper;

    @Test void protectedLivenessAndCachedMetricsSurviveFailedBusinessConnections() throws Exception {
        samples.sample();
        assertEquals(1,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
        double previous=meters.get("clientbus.notification.outbox.pending").gauge().value();
        clearInvocations(users);
        doThrow(new SQLException("Test database unavailable")).when(dataSource).getConnection();
        try(var http=HttpClient.newHttpClient()) {
            samples.sample();
            assertEquals(0,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
            var liveness=get(http,management("/health/liveness"),basic());
            assertEquals(200,liveness.statusCode(),liveness.body());
            assertEquals("UP",mapper.readTree(liveness.body()).path("status").asText());
            var readiness=get(http,management("/health/readiness"),basic());
            assertEquals(503,readiness.statusCode(),readiness.body());
            var metric=get(http,management("/metrics/clientbus.notification.outbox.pending"),basic());
            assertEquals(200,metric.statusCode(),metric.body());
            assertEquals(previous,mapper.readTree(metric.body()).path("measurements").get(0).path("value").asDouble());
            var valid=get(http,management("/metrics/clientbus.notification.outbox.sample.valid"),basic());
            assertEquals(200,valid.statusCode(),valid.body());
            assertEquals(0,mapper.readTree(valid.body()).path("measurements").get(0).path("value").asDouble());
            assertEquals(401,get(http,management("/health/liveness"),null).statusCode());
            assertEquals(401,get(http,management("/health/liveness"),wrongBasic()).statusCode());
            verify(users,never()).findById(any());
            // Ordinary JWT revocation checks still require the database; they are not silently bypassed.
            assertEquals(401,get(http,management("/health/liveness"),"Bearer "+adminToken).statusCode());
            verify(users).findById(adminId);
        } finally {doCallRealMethod().when(dataSource).getConnection();}
    }

    @Test void managementIdentityCannotAuthenticateApplicationRequestsOrPersistASession() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var allowed=get(http,management("/metrics"),basic());
            assertEquals(200,allowed.statusCode(),allowed.body());
            assertTrue(allowed.headers().allValues("Set-Cookie").isEmpty());
            for(String path:java.util.List.of("/api/notifications/summary","/api/events","/api/auth/me"))
                assertEquals(401,get(http,path,basic()).statusCode(),path);
            assertEquals(401,get(http,management("/metrics"),null).statusCode(),"Prior Basic request leaves no session");
            for(String path:java.util.List.of("/env","/configprops","/heapdump"))
                assertEquals(403,get(http,management(path),basic()).statusCode(),path);
        }
    }

    @Test void jwtAdminRoleAndRevocationChecksRemainInForce() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            assertEquals(200,get(http,management("/metrics"),"Bearer "+adminToken).statusCode());
            assertEquals(403,get(http,management("/metrics"),"Bearer "+employeeToken).statusCode());
            assertEquals(401,get(http,management("/metrics"),"Bearer "+disabledToken).statusCode());
            assertEquals(401,get(http,management("/metrics"),"Bearer malformed").statusCode());
        }
    }
}
