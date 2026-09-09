package kit.penny.clientbus.server.connector.telegram.authorization;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelAccountId}/telegram")
public class TelegramAuthorizationController {

    private final TelegramAuthorizationService authorizationService;

    public TelegramAuthorizationController(
            TelegramAuthorizationService authorizationService
    ) {
        this.authorizationService = authorizationService;
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