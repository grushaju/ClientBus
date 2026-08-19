package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.user.ChangePasswordRequest;
import kit.penny.clientbus.common.dto.user.UserDto;
import kit.penny.clientbus.server.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Пользователи", description = "API для управления пользователями")
public class UserController {

    private final UserService userService;

    public UserController(
            UserService userService
    ) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                userService.getUser(id)
        );
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @RequestBody ChangePasswordRequest request
    ) {

        userService.changePassword(
                id,
                request
        );

        return ResponseEntity.noContent().build();
    }
}