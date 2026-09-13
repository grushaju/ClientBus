package kit.penny.clientbus.server.connector.telegram.authorization;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelAccountId}/telegram")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Управление каналами Telegram", description = "API для управления Telegram")
public class TelegramAuthorizationController {

    private final TelegramAuthorizationService authorizationService;

    public TelegramAuthorizationController(
            TelegramAuthorizationService authorizationService
    ) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/start")
    public void startAuthorization(
            @PathVariable UUID channelAccountId
    ) {
        authorizationService.startAuthorization(channelAccountId);
    }

    @GetMapping("/status")
    public TelegramAuthorizationStatus getStatus(
            @PathVariable UUID channelAccountId
    ) {
        return authorizationService.getStatus(channelAccountId);
    }

    @PostMapping("/code")
    public void submitCode(
            @PathVariable UUID channelAccountId,
            @RequestBody AuthenticationCodeRequest request
    ) {
        authorizationService.submitCode(
                channelAccountId,
                request.code()
        );
    }

    @PostMapping("/password")
    public void submitPassword(
            @PathVariable UUID channelAccountId,
            @RequestBody AuthenticationPasswordRequest request
    ) {
        authorizationService.submitPassword(
                channelAccountId,
                request.password()
        );
    }

    @PostMapping("/email")
    public void submitEmail(
            @PathVariable UUID channelAccountId,
            @RequestBody EmailAddressRequest request
    ) {
        authorizationService.submitEmail(
                channelAccountId,
                request.email()
        );
    }

    @PostMapping("/stop")
    public void stop(
            @PathVariable UUID channelAccountId
    ) {
        authorizationService.stop(channelAccountId);
    }

    public record AuthenticationCodeRequest(
            String code
    ) {
    }

    public record AuthenticationPasswordRequest(
            String password
    ) {
    }

    public record EmailAddressRequest(
            String email
    ) {
    }
}