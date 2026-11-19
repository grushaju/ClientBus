package kit.penny.clientbus.server.security.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import kit.penny.clientbus.server.security.SseRequestSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) {
        this.jwtAuthenticationFilter =
                jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Value("${server.error.path:/error}") String errorPath
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        (request,response,exception) -> JwtAuthenticationFilter.writeStreamUnauthorized(response),
                        JwtAuthenticationFilter::isNotificationRequest))

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        // Only continuation of the already authenticated SSE request may bypass JWT re-auth.
                        .requestMatchers(SseRequestSecurity::isContinuation).permitAll()
                        // Error rendering cannot require a fresh JWT: a dependency failure would become
                        // an unrelated 401 and sign the browser out. Tomcat includes the error page when
                        // the response is already committed; its request URI still names the original route.
                        // Match the include target and error attributes, never all INCLUDE dispatches.
                        .requestMatchers(request -> {
                            String target = request.getContextPath() + errorPath;
                            return request.getDispatcherType() == DispatcherType.ERROR
                                    && target.equals(request.getRequestURI())
                                    || request.getDispatcherType() == DispatcherType.INCLUDE
                                    && target.equals(request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI))
                                    && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String
                                    && request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status
                                    && status >= 400 && status <= 599;
                        }).permitAll()

                        // React SPA
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/login",
                                "/favicon.ico",
                                "/notification.ogg",
                                "/assets/**",
                                "/inbox/**",
                                "/clients/**",
                                "/channels/**",
                                "/crm",
                                "/employees/**",
                                "/workspaces/**",
                                "/settings/**"
                        ).permitAll()

                        // Authentication API
                        .requestMatchers(
                                "/api/auth/**"
                        ).permitAll()

                        // OpenAPI
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/scalar/**"
                        ).permitAll()

                        // Everything else requires JWT
                        .anyRequest()
                        .authenticated()
                )

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {

        return new BCryptPasswordEncoder();
    }
}
