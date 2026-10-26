package kit.penny.clientbus.server.connector.telegram.authorization;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelAccountId}/telegram")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Управление каналами Telegram", description = "API для управления Telegram")
public class TelegramAuthorizationController {

    private final TelegramAuthorizationService authorizationService;
    private final HttpAccessPolicy accessPolicy;

    public TelegramAuthorizationController(
            TelegramAuthorizationService authorizationService, HttpAccessPolicy accessPolicy
    ) {
        this.authorizationService = authorizationService;
        this.accessPolicy = accessPolicy;
    }

    @PostMapping("/start")
    public void startAuthorization(
            @PathVariable UUID channelAccountId
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        authorizationService.startAuthorization(channelAccountId);
    }

    @GetMapping("/status")
    public TelegramAuthorizationStatus getStatus(
            @PathVariable UUID channelAccountId
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        return authorizationService.getStatus(channelAccountId);
    }

    @PostMapping("/code")
    public void submitCode(
            @PathVariable UUID channelAccountId,
            @RequestBody AuthenticationCodeRequest request
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
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
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
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
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        authorizationService.submitEmail(
                channelAccountId,
                request.email()
        );
    }

    @PostMapping("/disable")
    public void disable(
            @PathVariable UUID channelAccountId
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        authorizationService.disable(channelAccountId);
    }

    @PostMapping("/enable")
    public void enable(
            @PathVariable UUID channelAccountId
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        authorizationService.enable(channelAccountId);
    }

    @PostMapping("/disconnect")
    public void disconnect(
            @PathVariable UUID channelAccountId
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId);
        authorizationService.disconnect(channelAccountId);
    }

    @GetMapping("/find")
    public org.springframework.http.ResponseEntity<ClientAccountDiscoveryDto> find(
            @PathVariable UUID channelAccountId,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String username
    ) {
        accessPolicy.require(CHANNEL_ACCOUNT, channelAccountId);
        var found = authorizationService.find(
                channelAccountId,
                phone,
                username
        );
        accessPolicy.require(CHANNEL_ACCOUNT, channelAccountId);
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(found);
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
