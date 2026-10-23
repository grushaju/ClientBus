package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.AbstractEventDispatcher;
import kit.penny.clientbus.server.connector.ChannelEvent;
import kit.penny.clientbus.server.connector.IChannelListener;
import kit.penny.clientbus.server.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import kit.penny.tdlib.updates.UpdateAuthorizationState;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import kit.penny.clientbus.server.service.ChannelStatusService;
import java.util.List;
import java.util.UUID;

public class TelegramAuthorizationStateListener
        extends AbstractEventDispatcher
        implements ITdlibUpdateListener<TdApi.UpdateAuthorizationState> {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramAuthorizationStateListener.class
            );

    private final UUID channelId;
    private final UUID channelAccountId;
    private final ChannelStatusService channelStatus;
    private final TelegramProperties properties;
    private final TelegramAuthorizationManager authorizationManager;
    private final ObjectProvider<TelegramClient> telegramClientProvider;
    private final TelegramAccountProfileSynchronizer profileSynchronizer;

    private volatile UpdateAuthorizationState authorizationStateHandler;

    public TelegramAuthorizationStateListener(
            UUID channelId,
            UUID channelAccountId,
            ChannelStatusService channelStatus,
            TelegramAccountProfileSynchronizer profileSynchronizer,
            TelegramProperties properties,
            TelegramAuthorizationManager authorizationManager,
            ObjectProvider<TelegramClient> telegramClientProvider,
            List<IChannelListener> listeners
    ) {
        super(listeners);

        this.channelId = channelId;
        this.channelAccountId = channelAccountId;
        this.channelStatus = channelStatus;
        this.profileSynchronizer = profileSynchronizer;
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
                            "channelId={}, channelAccountId={}",
                    channelId,
                    channelAccountId,
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
            if (channelStatus.transition(channelAccountId, status, "CONNECTOR_AUTH_STATE")) {
                fireChannelChanged(new ChannelEvent(channelAccountId, status));
            }

        } catch (RuntimeException e) {
            log.error(
                    "Failed to update Telegram connection status: " +
                            "channelId={}, channelAccountId={}, status={}",
                    channelId,
                    channelAccountId,
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

                            profileSynchronizer.updateProfile(
                                    channelAccountId,
                                    user,
                                    null
                            );

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
}