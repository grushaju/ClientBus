package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.auth.LoginRequest;
import kit.penny.clientbus.common.dto.auth.LoginResponse;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {

        UserEntity user = userRepository
                .findByUsername(request.username())
                .or(() -> userRepository.findByEmail(request.email()))
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Invalid username or password"
                        )
                );

        if (!user.isEnabled()) {
            throw new DisabledException(
                    "User is disabled"
            );
        }

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash()
        )) {
            throw new BadCredentialsException(
                    "Invalid username or password"
            );
        }

        String token =
                jwtService.generateToken(user);

        return new LoginResponse(
                token,
                "Bearer"
        );
    }
}