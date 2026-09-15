package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import kit.penny.tdlib.updates.UpdateAuthorizationState;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

public class TelegramAuthorizationStateListener
        implements ITdlibUpdateListener<TdApi.UpdateAuthorizationState> {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramAuthorizationStateListener.class
            );

    private final UUID channelId;
    private final ChannelRepository channelRepository;
    private final TelegramProperties properties;
    private final TelegramAuthorizationManager authorizationManager;
    private final ObjectProvider<TelegramClient> telegramClientProvider;
    private final ChannelAccountRepository channelAccountRepository;

    private volatile UpdateAuthorizationState authorizationStateHandler;

    public TelegramAuthorizationStateListener(
            UUID channelId,
            ChannelRepository channelRepository,
            ChannelAccountRepository channelAccountRepository,
            TelegramProperties properties,
            TelegramAuthorizationManager authorizationManager,
            ObjectProvider<TelegramClient> telegramClientProvider
    ) {
        this.channelId = channelId;
        this.channelRepository = channelRepository;
        this.channelAccountRepository = channelAccountRepository;
        this.properties = properties;
        this.authorizationManager = authorizationManager;
        this.telegramClientProvider = telegramClientProvider;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateAuthorizationState notification
    ) {
        if (notification == null) {
            log.warn(
                    "Ignoring null Telegram authorization update: channelId={}",
                    channelId
            );
            return;
        }

        try {
            log.debug(
                    "TelegramAuthorizationStateListener received: {}",
                    notification.authorizationState == null
                            ? "null"
                            : notification.authorizationState
                            .getClass()
                            .getSimpleName()
            );

            getAuthorizationStateHandler()
                    .handleNotification(notification);

            TdApi.AuthorizationState state =
                    notification.authorizationState;

            if (state == null) {
                return;
            }

            ChannelConnectionStatus status =
                    mapStatus(state);

            if (status == null) {
                return;
            }

            log.debug(
                    "Telegram auth state mapped: channelId={}, " +
                            "tdlibState={}, connectionStatus={}",
                    channelId,
                    state.getClass().getSimpleName(),
                    status
            );

            updateStatus(status, state);

        } catch (RuntimeException e) {
            log.error(
                    "Failed to process Telegram authorization state: " +
                            "channelId={}",
                    channelId,
                    e
            );
        }
    }

    @Override
    public Class<TdApi.UpdateAuthorizationState>
    notificationType() {
        return TdApi.UpdateAuthorizationState.class;
    }

    private UpdateAuthorizationState getAuthorizationStateHandler() {
        UpdateAuthorizationState handler =
                authorizationStateHandler;

        if (handler == null) {
            synchronized (this) {
                handler = authorizationStateHandler;

                if (handler == null) {
                    handler = new UpdateAuthorizationState(
                            properties,
                            telegramClientProvider.getObject(),
                            authorizationManager
                    );

                    authorizationStateHandler = handler;
                }
            }
        }

        return handler;
    }

    private ChannelConnectionStatus mapStatus(
            TdApi.AuthorizationState state
    ) {
        if (state instanceof TdApi.AuthorizationStateReady) {
            loadTelegramAccount();
            return ChannelConnectionStatus.CONNECTED;
        }

        if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing
                || state instanceof TdApi.AuthorizationStateClosed) {
            return ChannelConnectionStatus.DISCONNECTED;
        }

        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters
                || state instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation
                || state instanceof TdApi.AuthorizationStateWaitCode
                || state instanceof TdApi.AuthorizationStateWaitPassword
                || state instanceof TdApi.AuthorizationStateWaitEmailAddress
                || state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            return ChannelConnectionStatus.CONNECTING;
        }

        return null;
    }

    private void updateStatus(
            ChannelConnectionStatus status,
            TdApi.AuthorizationState state
    ) {
        try {
            ChannelEntity channel =
                    channelRepository.findById(channelId)
                            .orElse(null);

            if (channel == null) {
                log.warn(
                        "Telegram channel not found: channelId={}",
                        channelId
                );
                return;
            }

            ChannelAccountEntity account =
                    channel.getAccount();

            if (account == null) {
                log.warn(
                        "Telegram channel account not found: channelId={}",
                        channelId
                );
                return;
            }

            log.debug(
                    "Telegram account loaded: " +
                            "channelAccountId={}, channelFound={}",
                    account.getId(),
                    true
            );

            if (channel.getStatus() == status) {
                log.debug(
                        "Telegram status already set: " +
                                "channelAccountId={}, " +
                                "currentStatus={}, requestedStatus={}",
                        account.getId(),
                        channel.getStatus(),
                        status
                );
                return;
            }

            log.debug(
                    "Telegram saving connection status: " +
                            "channelAccountId={}, status={}",
                    account.getId(),
                    status
            );

            channel.setStatus(status);

            channelRepository.save(channel);

            log.debug(
                    "Telegram connection status saved: " +
                            "channelAccountId={}, status={}, tdlibState={}",
                    account.getId(),
                    channel.getStatus(),
                    state.getClass().getSimpleName()
            );

        } catch (RuntimeException e) {
            log.error(
                    "Failed to update Telegram connection status: " +
                            "channelId={}, status={}",
                    channelId,
                    status,
                    e
            );
        }
    }

    private void loadTelegramAccount() {
        try {
            telegramClientProvider
                    .getObject()
                    .sendAsync(new TdApi.GetMe())
                    .thenAccept(response -> {
                        try {
                            if (response.getError().isPresent()) {
                                log.warn(
                                        "Failed to load Telegram account: " +
                                                "channelId={}, error={}",
                                        channelId,
                                        response.getError()
                                                .get()
                                                .message
                                );
                                return;
                            }

                            TdApi.User user =
                                    response.getObject().orElse(null);

                            if (user == null) {
                                log.warn(
                                        "Telegram account response is empty: " +
                                                "channelId={}",
                                        channelId
                                );
                                return;
                            }

                            updateChannelAccount(user);

                        } catch (RuntimeException e) {
                            log.error(
                                    "Failed to process Telegram account " +
                                            "response: channelId={}",
                                    channelId,
                                    e
                            );
                        }
                    })
                    .exceptionally(e -> {
                        log.error(
                                "Telegram GetMe request failed: channelId={}",
                                channelId,
                                e
                        );
                        return null;
                    });

        } catch (RuntimeException e) {
            log.error(
                    "Failed to request Telegram account: channelId={}",
                    channelId,
                    e
            );
        }
    }

    private void updateChannelAccount(TdApi.User user) {
        try {
            ChannelEntity channel =
                    channelRepository.findById(channelId)
                            .orElse(null);

            if (channel == null) {
                log.warn(
                        "Telegram channel not found while updating account: " +
                                "channelId={}",
                        channelId
                );
                return;
            }

            ChannelAccountEntity account =
                    channel.getAccount();

            if (account == null) {
                log.warn(
                        "Telegram channel account not found: channelId={}",
                        channelId
                );
                return;
            }

            account.setExternalId(
                    Long.toString(user.id)
            );

            account.setUsername(
                    user.usernames != null
                            && user.usernames.activeUsernames != null
                            && user.usernames.activeUsernames.length > 0
                            ? user.usernames.activeUsernames[0]
                            : null
            );

            account.setPhone(user.phoneNumber);

            account.setDisplayName(
                    buildDisplayName(
                            user.firstName,
                            user.lastName
                    )
            );

            channelAccountRepository.save(account);

            log.debug(
                    "Telegram account data saved: " +
                            "channelAccountId={}, externalId={}, " +
                            "username={}, displayName={}",
                    account.getId(),
                    account.getExternalId(),
                    account.getUsername(),
                    account.getDisplayName()
            );

        } catch (RuntimeException e) {
            log.error(
                    "Failed to update Telegram account data: channelId={}",
                    channelId,
                    e
            );
        }
    }

    private String buildDisplayName(
            String firstName,
            String lastName
    ) {
        String first =
                firstName == null ? "" : firstName.trim();

        String last =
                lastName == null ? "" : lastName.trim();

        if (first.isEmpty()) {
            return last.isEmpty() ? null : last;
        }

        if (last.isEmpty()) {
            return first;
        }

        return first + " " + last;
    }
}
