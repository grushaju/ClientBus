package kit.penny.clientbus.server.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.net.http.HttpClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"management.endpoints.web.base-path=/ops","server.servlet.context-path=/desk"})
class ManagementBasePathIntegrationTest extends ManagementAuthenticationIntegrationSupport {
    @Test void configuredManagementAndContextPathsKeepBasicIsolated() throws Exception {
        clearInvocations(users);
        try(var http=HttpClient.newHttpClient()) {
            assertEquals(200,get(http,management("/health/liveness"),basic()).statusCode());
            assertEquals(200,get(http,management("/metrics"),basic()).statusCode());
            assertEquals(401,get(http,management("/metrics"),wrongBasic()).statusCode());
            assertEquals(401,get(http,"/api/notifications/summary",basic()).statusCode());
            verify(users,never()).findById(any());
        }
    }
}
