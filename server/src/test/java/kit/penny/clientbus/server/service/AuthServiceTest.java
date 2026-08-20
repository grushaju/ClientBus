package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.auth.LoginRequest;
import kit.penny.clientbus.common.dto.auth.LoginResponse;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        authService =
                new AuthService(
                        userRepository,
                        passwordEncoder,
                        jwtService
                );
    }

    @Test
    void loginByUsername_shouldReturnToken() {

        UserEntity user =
                new UserEntity(
                        "ivan",
                        "ivan@example.com",
                        "HASH"
                );

        user.setId(UUID.randomUUID());

        LoginRequest request =
                new LoginRequest(
                        "ivan",
                        "password",
                        null
                );

        when(userRepository.findByUsername("ivan"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "password",
                "HASH"
        )).thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("jwt-token");

        LoginResponse response =
                authService.login(request);

        assertThat(response)
                .isNotNull();

        assertThat(response.accessToken())
                .isEqualTo("jwt-token");

        assertThat(response.tokenType())
                .isEqualTo("Bearer");

        verify(userRepository)
                .findByUsername("ivan");

        verify(passwordEncoder)
                .matches("password", "HASH");

        verify(jwtService)
                .generateToken(user);

        verify(userRepository, never())
                .findByEmail(any());
    }

    @Test
    void loginByEmail_shouldFallbackToEmail() {

        UserEntity user =
                new UserEntity(
                        "ivan",
                        "ivan@example.com",
                        "HASH"
                );

        user.setId(UUID.randomUUID());

        LoginRequest request =
                new LoginRequest(
                        null,
                        "password",
                        "ivan@example.com"
                );

        when(userRepository.findByUsername(null))
                .thenReturn(Optional.empty());

        when(userRepository.findByEmail(
                "ivan@example.com"
        )).thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "password",
                "HASH"
        )).thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("jwt-token");

        LoginResponse response =
                authService.login(request);

        assertThat(response.accessToken())
                .isEqualTo("jwt-token");

        verify(userRepository)
                .findByEmail("ivan@example.com");
    }

    @Test
    void login_userNotFound_shouldThrow() {

        LoginRequest request =
                new LoginRequest(
                        "unknown",
                        "password",
                        null
                );

        when(userRepository.findByUsername("unknown"))
                .thenReturn(Optional.empty());

        when(userRepository.findByEmail(null))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.login(request)
        )
                .isInstanceOf(
                        BadCredentialsException.class
                )
                .hasMessage(
                        "Invalid username or password"
                );

        verify(passwordEncoder, never())
                .matches(any(), any());

        verify(jwtService, never())
                .generateToken(any());
    }

    @Test
    void login_disabledUser_shouldThrow() {

        UserEntity user =
                new UserEntity(
                        "ivan",
                        "ivan@example.com",
                        "HASH"
                );

        user.setId(UUID.randomUUID());
        user.setEnabled(false);

        LoginRequest request =
                new LoginRequest(
                        "ivan",
                        "password",
                        null
                );

        when(userRepository.findByUsername("ivan"))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                authService.login(request)
        )
                .isInstanceOf(
                        DisabledException.class
                );

        verify(passwordEncoder, never())
                .matches(any(), any());

        verify(jwtService, never())
                .generateToken(any());
    }

    @Test
    void login_wrongPassword_shouldThrow() {

        UserEntity user =
                new UserEntity(
                        "ivan",
                        "ivan@example.com",
                        "HASH"
                );

        user.setId(UUID.randomUUID());

        LoginRequest request =
                new LoginRequest(
                        "ivan",
                        "wrong",
                        null
                );

        when(userRepository.findByUsername("ivan"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "wrong",
                "HASH"
        )).thenReturn(false);

        assertThatThrownBy(() ->
                authService.login(request)
        )
                .isInstanceOf(
                        BadCredentialsException.class
                );

        verify(jwtService, never())
                .generateToken(any());
    }
}