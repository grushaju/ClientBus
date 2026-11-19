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
        // Значение по умолчанию в конфигурации было бы дырой, а не удобством: публичный ключ
        // подписи позволяет любому выдать себя за любого. Поэтому здесь отказ с названием
        // переменной, а не тихая работа с тем, что нашлось.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Ключ подписи clientbus.jwt.secret не задан. Укажите "
                    + "CLIENTBUS_JWT_SECRET (base64 не короче 32 байт); запуск с ключом из "
                    + "репозитория запрещён.");
        }

        byte[] key;
        try {
            key = Decoders.BASE64.decode(secret);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("clientbus.jwt.secret обязан быть base64-строкой "
                    + "(например, вывод openssl rand -base64 32)", failure);
        }

        try {
            this.secretKey = Keys.hmacShaKeyFor(key);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("clientbus.jwt.secret слишком короткий: ключу HMAC-SHA256 "
                    + "нужно не меньше 256 бит (32 байта, base64 из 44 символов)", failure);
        }

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
                .claim("username", user.getUsername())
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

    /** Verified expiry for a long-lived SSE session; never retain the raw token. */
    public java.time.Instant getExpiresAt(String token) {
        return getClaims(token).getExpiration().toInstant();
    }

    /**
     * Получение username из JWT.
     */
    public String getUsername(String token) {

        return getClaims(token)
                .get("username", String.class);
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
