package kit.penny.clientbus.server.security.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringJUnitWebConfig({SecurityConfig.class, SecurityErrorDispatchTest.Dependencies.class})
class SecurityErrorDispatchTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(JwtService.class), mock(UserRepository.class));
        }
    }

    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired WebApplicationContext context;

    @Test void committedErrorIncludeReachesTheErrorRendererWithoutReauthentication() throws Exception {
        var request = request("/api/events", DispatcherType.INCLUDE);
        request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/desk/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        var response = new MockHttpServletResponse();
        response.setCommitted(true);
        var reached = new AtomicBoolean();

        assertDoesNotThrow(() -> springSecurityFilterChain.doFilter(request, response,
                (req, res) -> reached.set(true)));
        assertTrue(reached.get());
    }

    @Test void errorDispatchStillReachesTheRenderer() throws Exception {
        assertTrue(reachesApplication(request("/error", DispatcherType.ERROR)));
    }

    @Test void directErrorRequestsStayProtectedEvenWithErrorAttributes() throws Exception {
        var request = request("/error", DispatcherType.REQUEST);
        request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/desk/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        assertFalse(reachesApplication(request));
    }

    @Test void businessIncludesAndOrdinaryErrorIncludesStayProtected() throws Exception {
        var business = request("/api/notifications/summary", DispatcherType.INCLUDE);
        business.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/desk/api/notifications/summary");
        business.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events");
        business.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        assertFalse(reachesApplication(business));

        var ordinaryInclude = request("/error", DispatcherType.INCLUDE);
        ordinaryInclude.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/desk/error");
        assertFalse(reachesApplication(ordinaryInclude));
    }

    private MockHttpServletRequest request(String path, DispatcherType type) {
        var request = new MockHttpServletRequest(context.getServletContext(), "GET", "/desk" + path);
        request.setContextPath("/desk");
        request.setServletPath(path);
        request.setDispatcherType(type);
        return request;
    }

    private boolean reachesApplication(MockHttpServletRequest request) throws Exception {
        var reached = new AtomicBoolean();
        springSecurityFilterChain.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        return reached.get();
    }
}
