package kit.penny.clientbus.server.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtService(
            @Value("${clientbus.jwt.secret}") String secret,
            @Value("${clientbus.jwt.expiration}") long expiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(secret)
        );

        this.expiration = expiration;
    }

    /**
     * Создание JWT для пользователя.
     */
    public String generateToken(UserEntity user) {

        Date now = new Date();

        Date expirationDate =
                new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("login", user.getLogin())
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Получение User ID из JWT.
     */
    public UUID getUserId(String token) {

        String subject = getClaims(token)
                .getSubject();

        return UUID.fromString(subject);
    }

    /**
     * Получение login из JWT.
     */
    public String getLogin(String token) {

        return getClaims(token)
                .get("login", String.class);
    }

    /**
     * Проверка JWT.
     */
    public boolean isTokenValid(String token) {

        try {

            Claims claims = getClaims(token);

            return claims.getExpiration()
                    .after(new Date());

        } catch (Exception e) {

            return false;
        }
    }

    /**
     * Получение Claims из JWT.
     */
    private Claims getClaims(String token) {

        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
