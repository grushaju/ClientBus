package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
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

    public TelegramAuthorizationService(
            TelegramClientLifecycleService lifecycleService,
            TelegramChannelAccountService channelAccountService
    ) {
        this.lifecycleService = lifecycleService;
        this.channelAccountService = channelAccountService;
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

    public void stop(
            UUID channelAccountId
    ) {
        channelAccountService.stop(channelAccountId);
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
}