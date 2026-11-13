package kit.penny.clientbus.server.web;

import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.config.SecurityConfig;
import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmSpaRoutingTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityConfig.class, WebConfig.class);
        context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void crmDeepLinkServesTheAppWithoutAnAuthorizationHeader() throws Exception {
        mvc.perform(get("/crm").param("tab","appointments"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
    @Test void publicCrmPageDoesNotExposeCrmApiWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/crm/projects").param("tab","appointments"))
                .andExpect(status().isUnauthorized());
    }
    @Test void retiredAppointmentsRouteHasNoPageOrRedirect() throws Exception {
        mvc.perform(get("/appointments").with(user("employee").roles("EMPLOYEE")))
                .andExpect(status().isNotFound());
    }

    // Lite-mode config: registered explicitly via context.register below. Must stay free of
    // @Configuration/@Component, otherwise the application's package scan in every
    // @SpringBootTest context would pick it up and replace the real JwtAuthenticationFilter
    // with the mock-backed bean below (regression seen in Stage 12.7).
    @EnableWebMvc
    @Import(SpaController.class)
    static class WebConfig {
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(JwtService.class),mock(UserRepository.class));
        }
    }
}
