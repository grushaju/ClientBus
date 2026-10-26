package kit.penny.clientbus.server.security.config;

import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** DB-independent credentials never enter the application filter chain or its authentication manager. */
@Configuration(proxyBeanMethods=false)
public class ManagementSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http,JwtAuthenticationFilter jwt,
            ManagementAccessProperties credentials) throws Exception {
        AuthenticationEntryPoint unauthorized=(request,response,error)->{
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Basic realm=\"clientbus-management\", charset=\"UTF-8\"");
            response.setStatus(401);
        };
        http.securityMatcher(managementRequests())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(authenticationManager(credentials))
                .httpBasic(basic->basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(errors->errors.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request,response,error)->response.setStatus(403)))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class,MetricsEndpoint.class)).hasRole("SUPER_ADMIN")
                        .anyRequest().denyAll())
                // Ordinary admin JWTs keep their normal DB/revocation checks. Basic must never invoke that path.
                .addFilterBefore(new BearerOnlyFilter(jwt),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static AuthenticationManager authenticationManager(ManagementAccessProperties credentials) {
        if(!credentials.enabled()) return authentication->{throw new BadCredentialsException("Management credentials unavailable");};
        var users=new InMemoryUserDetailsManager(User.withUsername(credentials.username())
                .password(credentials.passwordHash()).roles("SUPER_ADMIN").build());
        var provider=new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        return new ProviderManager(provider);
    }

    private static RequestMatcher managementRequests() {
        return new OrRequestMatcher(EndpointRequest.toAnyEndpoint(),request->{
            String path=request.getRequestURI().substring(request.getContextPath().length());
            return path.equals("/actuator") || path.startsWith("/actuator/");
        });
    }

    @Bean FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter jwt) {
        var registration=new FilterRegistrationBean<>(jwt);
        registration.setEnabled(false);
        return registration;
    }

    private static final class BearerOnlyFilter extends OncePerRequestFilter {
        private final JwtAuthenticationFilter jwt;
        private BearerOnlyFilter(JwtAuthenticationFilter jwt) {this.jwt=jwt;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                throws ServletException,IOException {
            String authorization=request.getHeader(HttpHeaders.AUTHORIZATION);
            if(authorization!=null && authorization.startsWith("Bearer ")) jwt.doFilter(request,response,chain);
            else chain.doFilter(request,response);
        }
    }
}
