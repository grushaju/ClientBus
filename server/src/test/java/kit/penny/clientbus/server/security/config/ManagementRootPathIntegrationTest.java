package kit.penny.clientbus.server.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.net.http.HttpClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties="management.endpoints.web.base-path=/")
class ManagementRootPathIntegrationTest extends ManagementAuthenticationIntegrationSupport {
    @Test void rootManagementBasePathDoesNotCaptureTheBusinessApi() throws Exception {
        clearInvocations(users);
        try(var http=HttpClient.newHttpClient()) {
            assertEquals(200,get(http,management("/health/liveness"),basic()).statusCode());
            assertEquals(200,get(http,management("/metrics"),basic()).statusCode());
            assertEquals(401,get(http,"/api/notifications/summary",basic()).statusCode());
            assertEquals(401,get(http,"/api/events",basic()).statusCode());
            assertEquals(401,get(http,management("/metrics"),null).statusCode());
            verify(users,never()).findById(any());
        }
    }
}
