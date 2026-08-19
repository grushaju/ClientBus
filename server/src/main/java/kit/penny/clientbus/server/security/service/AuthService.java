package kit.penny.clientbus.server.security.service;

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

        UserEntity user =
                userRepository.findByLogin(request.login())
                        .orElseThrow(() ->
                                new BadCredentialsException(
                                        "Invalid login or password"
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
                    "Invalid login or password"
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