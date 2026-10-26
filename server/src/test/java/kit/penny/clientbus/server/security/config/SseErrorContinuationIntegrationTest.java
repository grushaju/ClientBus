package kit.penny.clientbus.server.security.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.security.SseRequestSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only a servlet-container continuation can use the exception; a new HTTP request still needs JWT. */
@SpringBootTest(properties = {"server.servlet.context-path=/desk", "clientbus.notifications.sse-enabled=false"})
@AutoConfigureMockMvc
class SseErrorContinuationIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;

    @ParameterizedTest @EnumSource(value = DispatcherType.class, names = {"ERROR", "ASYNC"})
    void admittedStreamErrorKeepsTheOriginalFailureInsteadOfAuthenticatingAgain(DispatcherType type) throws Exception {
        mvc.perform(get("/desk/error").contextPath("/desk").with(request -> { request.setDispatcherType(type); return request; })
                        .requestAttr(SseRequestSecurity.ADMITTED, true)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.status").value(500));
    }

    @Test void aNewRequestCannotUseAnErrorAttributeToBypassAuthentication() throws Exception {
        mvc.perform(get("/desk/error").contextPath("/desk")
                        .requestAttr(SseRequestSecurity.ADMITTED, true)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/desk/api/events").contextPath("/desk"))
                .andExpect(status().isUnauthorized());
    }

    @Test void aBusinessFailureKeepsItsStatusWithoutAnUnrelatedAuthenticationError() throws Exception {
        mvc.perform(get("/desk/error").contextPath("/desk").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/conversations/test/read")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
    }

    @Test void otherErrorAndAsyncDispatchesStayProtected() throws Exception {
        mvc.perform(get("/desk/api/notifications/summary").contextPath("/desk").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/desk/api/events"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/desk/api/notifications/summary").contextPath("/desk").with(request -> { request.setDispatcherType(DispatcherType.ASYNC); return request; }))
                .andExpect(status().is4xxClientError());
    }

    @Test void thePublicSoundClipHasNoEmployeeOrNotificationData() throws Exception {
        mvc.perform(get("/desk/notification.ogg").contextPath("/desk"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/ogg"));
    }
}
