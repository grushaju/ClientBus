package kit.penny.clientbus.server.security.jwt;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            "FnQHtz4yCzaBgma1a25xV30mShdAQZx/33msarZ57f0=";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {

        jwtService =
                new JwtService(
                        SECRET,
                        1_800_000
                );
    }

    @Test
    void generateToken_shouldCreateValidToken() {

        UserEntity user =
                new UserEntity(
                        "testuser",
                        "test@example.com",
                        "hash",
                        UserRole.EMPLOYEE
                );

        UUID userId = UUID.randomUUID();
        user.setId(userId);

        String token =
                jwtService.generateToken(user);

        assertThat(token)
                .isNotBlank();

        assertThat(
                jwtService.isTokenValid(token)
        ).isTrue();
    }

    @Test
    void getUserId_shouldReturnUserId() {

        UserEntity user =
                new UserEntity(
                        "testuser",
                        "test@example.com",
                        "hash",
                        UserRole.EMPLOYEE
                );

        UUID userId = UUID.randomUUID();
        user.setId(userId);

        String token =
                jwtService.generateToken(user);

        assertThat(
                jwtService.getUserId(token)
        ).isEqualTo(userId);
    }

    @Test
    void getUsername_shouldReturnUsername() {

        UserEntity user =
                new UserEntity(
                        "testuser",
                        "test@example.com",
                        "hash",
                        UserRole.EMPLOYEE
                );

        user.setId(UUID.randomUUID());

        String token =
                jwtService.generateToken(user);

        assertThat(
                jwtService.getUsername(token)
        ).isEqualTo("testuser");
    }

    @Test
    void invalidToken_shouldReturnFalse() {

        assertThat(
                jwtService.isTokenValid(
                        "invalid.jwt.token"
                )
        ).isFalse();
    }

    @Test
    void modifiedToken_shouldReturnFalse() {

        UserEntity user =
                new UserEntity(
                        "testuser",
                        "test@example.com",
                        "hash",
                        UserRole.EMPLOYEE
                );

        user.setId(UUID.randomUUID());

        String token =
                jwtService.generateToken(user);

        // Change signed bytes; changing only the last Base64URL symbol may affect unused padding bits.
        int signatureStart = token.lastIndexOf('.') + 1;
        byte[] signature = Base64.getUrlDecoder().decode(token.substring(signatureStart));
        signature[0] ^= 1;
        String modifiedToken = token.substring(0, signatureStart)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThat(jwtService.isTokenValid(token)).isTrue();

        assertThat(
                jwtService.isTokenValid(modifiedToken)
        ).isFalse();
    }

    @Test
    void expiredToken_shouldReturnFalse() {

        JwtService expiredJwtService =
                new JwtService(
                        SECRET,
                        -1000
                );

        UserEntity user =
                new UserEntity(
                        "testuser",
                        "test@example.com",
                        "hash",
                        UserRole.EMPLOYEE
                );

        user.setId(UUID.randomUUID());

        String token =
                expiredJwtService.generateToken(user);

        assertThat(
                expiredJwtService.isTokenValid(token)
        ).isFalse();
    }
}
