package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
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

    public TelegramAuthorizationService(
            TelegramClientLifecycleService lifecycleService
    ) {
        this.lifecycleService = lifecycleService;
    }

    public TelegramAuthorizationStatus getStatus(
            UUID channelAccountId
    ) {
        TelegramAuthorizationManager authorizationManager =
                getAuthorizationManager(channelAccountId);

        return new TelegramAuthorizationStatus(
                authorizationManager.haveAuthorization(),
                authorizationManager.isWaitAuthenticationCode(),
                authorizationManager.isWaitAuthenticationPassword(),
                authorizationManager.isWaitEmailAddress(),
                authorizationManager.isStateClosed()
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

    private TelegramAuthorizationManager getAuthorizationManager(
            UUID channelAccountId
    ) {
        TelegramClientContext context =
                lifecycleService.require(channelAccountId);

        return context.authorizationManager();
    }

    public record TelegramAuthorizationStatus(
            boolean authorized,
            boolean waitingAuthenticationCode,
            boolean waitingAuthenticationPassword,
            boolean waitingEmailAddress,
            boolean stateClosed
    ) {
    }
}