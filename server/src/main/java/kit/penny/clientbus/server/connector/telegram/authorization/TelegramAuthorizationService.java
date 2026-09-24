package kit.penny.clientbus.server.connector.telegram.authorization;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;
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

    public TelegramAuthorizationService(
            TelegramClientLifecycleService lifecycleService,
            TelegramChannelAccountService channelAccountService,
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            CurrentUserService currentUserService
    ) {
        this.lifecycleService = lifecycleService;
        this.channelAccountService = channelAccountService;
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.currentUserService = currentUserService;
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

        TelegramClientContext context =
                lifecycleService.require(channelAccountId);

        TelegramUserService telegramUserService =
                context.applicationContext()
                        .getBean(TelegramUserService.class);

        TdlibResponse<TdApi.User> response;

        if (hasPhone) {

            response = telegramUserService
                    .searchUserByPhoneNumber(
                            phone.trim()
                    )
                    .join();

        } else {

            response = telegramUserService
                    .searchUserByUsername(
                            normalizeUsername(username)
                    )
                    .join();
        }

        if (response.getError().isPresent()) {

            TdApi.Error error =
                    response.getError().get();

            throw new IllegalStateException(
                    "Telegram user search failed: "
                            + error.message
            );
        }

        TdApi.User user =
                response.getObject()
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Telegram user not found"
                                )
                        );

        String externalId =
                String.valueOf(user.id);

        String resolvedUsername =
                extractUsername(user);

        String displayName =
                buildDisplayName(
                        user.firstName,
                        user.lastName
                );

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
                resolvedUsername,
                emptyToNull(user.phoneNumber),
                displayName,
                existingClientAccountId
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

    private String extractUsername(
            TdApi.User user
    ) {
        if (user.usernames == null
                || user.usernames.activeUsernames == null
                || user.usernames.activeUsernames.length == 0) {

            return null;
        }

        return user.usernames.activeUsernames[0];
    }

    private String buildDisplayName(
            String firstName,
            String lastName
    ) {
        String first =
                emptyToNull(firstName);

        String last =
                emptyToNull(lastName);

        if (first == null) {
            return last;
        }

        if (last == null) {
            return first;
        }

        return first + " " + last;
    }

    private String emptyToNull(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }
}