package kit.penny.clientbus.server.connector.telegram.authorization;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.dto.channel.ClientAccountAvatarPreviewDto;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Provides ClientBus-level access to Telegram authorization state
 * and authentication input.
 *
 * <p>The service operates on a Telegram client belonging to a specific
 * channel account. Authorization state itself is managed by TDLib and
 * TelegramAuthorizationManager.</p>
 *
 * @author Pavel Grushin
 */

@Service
public class TelegramAuthorizationService {

    private final TelegramClientLifecycleService lifecycleService;
    private final TelegramChannelAccountService channelAccountService;

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final CurrentUserService currentUserService;
    private final IChannelConnectorRegistry connectors;

    public TelegramAuthorizationService(
            TelegramClientLifecycleService lifecycleService,
            TelegramChannelAccountService channelAccountService,
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            CurrentUserService currentUserService,
            IChannelConnectorRegistry connectors
    ) {
        this.lifecycleService = lifecycleService;
        this.channelAccountService = channelAccountService;
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.currentUserService = currentUserService;
        this.connectors = connectors;
    }

    public void startAuthorization(
            UUID channelAccountId
    ) {
        channelAccountService.create(channelAccountId);
    }

    public TelegramAuthorizationStatus getStatus(
            UUID channelAccountId
    ) {
        return mapStatus(
                getAuthorizationManager(channelAccountId)
                        .getStatus()
        );
    }

    public void submitCode(
            UUID channelAccountId,
            String code
    ) {
        getAuthorizationManager(channelAccountId)
                .checkAuthenticationCode(code);
    }

    public void submitPassword(
            UUID channelAccountId,
            String password
    ) {
        getAuthorizationManager(channelAccountId)
                .checkAuthenticationPassword(password);
    }

    public void submitEmail(
            UUID channelAccountId,
            String email
    ) {
        getAuthorizationManager(channelAccountId)
                .checkEmailAddress(email);
    }

    private kit.penny.tdlib.updates.TelegramAuthorizationManager
    getAuthorizationManager(
            UUID channelAccountId
    ) {
        TelegramClientContext context =
                lifecycleService.require(channelAccountId);

        return context.authorizationManager();
    }

    public void disable(UUID channelAccountId) {
        channelAccountService.disable(channelAccountId);
    }

    public void enable(UUID channelAccountId) {
        channelAccountService.enable(channelAccountId);
    }

    public void disconnect(UUID channelAccountId) {
        channelAccountService.disconnect(channelAccountId);
    }

    public ClientAccountDiscoveryDto find(
            UUID channelAccountId,
            String phone,
            String username
    ) {
        boolean hasPhone =
                phone != null && !phone.isBlank();

        boolean hasUsername =
                username != null && !username.isBlank();

        if (hasPhone == hasUsername) {
            throw new IllegalArgumentException(
                    "Exactly one of phone or username must be specified"
            );
        }

        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findById(channelAccountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "ChannelAccount not found: "
                                                + channelAccountId
                                )
                        );

        if (channelAccount.getChannel().getType()
                != ChannelType.TELEGRAM) {

            throw new IllegalArgumentException(
                    "ChannelAccount is not a Telegram account"
            );
        }

        currentUserService.requireWorkspaceAccess(
                channelAccount
                        .getChannel()
                        .getWorkspace()
                        .getId()
        );

        if (channelAccount.getChannel().getStatus()
                != ChannelConnectionStatus.CONNECTED) {

            throw new IllegalStateException(
                    "Telegram channel is not connected"
            );
        }

        var found = connectors.getConnector(ChannelType.TELEGRAM).handle(
                new DiscoverClientAccountCommand(channelAccountId,
                        hasPhone ? phone.trim() : null,
                        hasUsername ? normalizeUsername(username) : null));
        // A lookup may outlive membership or account revocation while waiting on the platform.
        currentUserService.requireWorkspaceAccess(channelAccount.getChannel().getWorkspace().getId());
        String externalId = found.externalId();

        UUID existingClientAccountId =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                externalId
                        )
                        .map(account -> account.getId())
                        .orElse(null);

        return new ClientAccountDiscoveryDto(
                externalId,
                found.username(),
                found.phone(),
                found.displayName(),
                existingClientAccountId,
                found.avatarPreview() == null ? null : new ClientAccountAvatarPreviewDto(
                        found.avatarPreview().contentType(), found.avatarPreview().base64())
        );
    }

    private TelegramAuthorizationStatus mapStatus(
            kit.penny.tdlib.updates.TelegramAuthorizationStatus status
    ) {
        return switch (status) {
            case WAIT_PHONE_NUMBER ->
                    TelegramAuthorizationStatus.WAIT_PHONE_NUMBER;
            case WAIT_CODE ->
                    TelegramAuthorizationStatus.WAIT_CODE;
            case WAIT_PASSWORD ->
                    TelegramAuthorizationStatus.WAIT_PASSWORD;
            case WAIT_EMAIL ->
                    TelegramAuthorizationStatus.WAIT_EMAIL;
            case READY ->
                    TelegramAuthorizationStatus.READY;
            case ERROR ->
                    TelegramAuthorizationStatus.ERROR;
            case CLOSED ->
                    TelegramAuthorizationStatus.CLOSED;
        };
    }

    private String normalizeUsername(
            String username
    ) {
        String normalized = username.trim();

        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Username must not be blank"
            );
        }

        return normalized;
    }

}
