package kit.penny.clientbus.server.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.UserPrincipal;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter
        extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        boolean stream=request.getRequestURI().equals(request.getContextPath()+"/api/events");
        if (stream) {
            response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Accel-Buffering","no");
        }

        String authorization =
                request.getHeader(HttpHeaders.AUTHORIZATION);

        /*
         * Authorization отсутствует.
         *
         * Здесь НЕ возвращаем 401.
         * Решение о том, разрешён ли anonymous request,
         * принимает SecurityConfig.
         */
        if (authorization == null ||
                authorization.isBlank()) {

            filterChain.doFilter(request, response);
            return;
        }

        /*
         * Проверяем Bearer.
         */
        if (!authorization.startsWith("Bearer ")) {
            if (stream) { writeStreamUnauthorized(response); return; }

            response.setStatus(
                    HttpServletResponse.SC_UNAUTHORIZED
            );

            response.setContentType("application/json");

            response.getWriter().write("""
                    {
                        "error": "Unauthorized",
                        "message": "Authorization header must contain Bearer token"
                    }
                    """);

            return;
        }

        String token =
                authorization.substring(7).trim();

        if (token.isEmpty()) {
            if (stream) { writeStreamUnauthorized(response); return; }

            response.setStatus(
                    HttpServletResponse.SC_UNAUTHORIZED
            );

            return;
        }

        try {

            /*
             * Проверяем JWT.
             */
            if (!jwtService.isTokenValid(token)) {
                if (stream) { writeStreamUnauthorized(response); return; }

                response.setStatus(
                        HttpServletResponse.SC_UNAUTHORIZED
                );

                response.setContentType("application/json");

                response.getWriter().write("""
                        {
                            "error": "Unauthorized",
                            "message": "Invalid or expired token"
                        }
                        """);

                return;
            }

            /*
             * Получаем User ID из JWT.sub
             */
            UUID userId =
                    jwtService.getUserId(token);

            /*
             * Загружаем пользователя.
             */
            UserEntity user =
                    userRepository.findById(userId)
                            .orElse(null);

            if (user == null) {
                if (stream) { writeStreamUnauthorized(response); return; }

                response.setStatus(
                        HttpServletResponse.SC_UNAUTHORIZED
                );

                response.setContentType("application/json");

                response.getWriter().write("""
                        {
                            "error": "Unauthorized",
                            "message": "User not found"
                        }
                        """);

                return;
            }

            /*
             * Проверяем, что пользователь активен.
             */
            if (!user.isEnabled()) {
                if (stream) { writeStreamUnauthorized(response); return; }

                response.setStatus(
                        HttpServletResponse.SC_UNAUTHORIZED
                );

                response.setContentType("application/json");

                response.getWriter().write("""
                        {
                            "error": "Unauthorized",
                            "message": "User is disabled"
                        }
                        """);

                return;
            }

            /*
             * Создаём Principal.
             */
            UserPrincipal principal =
                    new UserPrincipal(user);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities()
                    );

            authentication.setDetails(new VerifiedSession(jwtService.getExpiresAt(token)));

            /*
             * Записываем Authentication
             * в текущий SecurityContext.
             */
            SecurityContextHolder
                    .getContext()
                    .setAuthentication(authentication);

        } catch (Exception e) {

            /*
             * Любая ошибка обработки JWT
             * означает невалидный токен.
             */
            SecurityContextHolder
                    .clearContext();

            if (stream) { writeStreamUnauthorized(response); return; }

            response.setStatus(
                    HttpServletResponse.SC_UNAUTHORIZED
            );

            response.setContentType("application/json");

            response.getWriter().write("""
                    {
                        "error": "Unauthorized",
                        "message": "Invalid authentication token"
                    }
                    """);

            return;
        }

        /*
         * Передаём запрос следующему фильтру.
         */
        filterChain.doFilter(request, response);
    }

    public record VerifiedSession(java.time.Instant expiresAt) {}

    public static void writeStreamUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("Cache-Control","no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"correlationId\":\""+UUID.randomUUID()+"\"}");
    }
}
