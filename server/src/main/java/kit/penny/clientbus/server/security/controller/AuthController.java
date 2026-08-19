package kit.penny.clientbus.server.security.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.auth.LoginRequest;
import kit.penny.clientbus.common.dto.auth.LoginResponse;
import kit.penny.clientbus.server.security.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Авторизация", description = "API для управления авторизацией")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest request
    ) {

        return ResponseEntity.ok(
                authService.login(request)
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {

        return ResponseEntity.noContent().build();
    }
}