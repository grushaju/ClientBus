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
            LoggerFactory.getLogger(TelegramAuthorizationStateListener.class);

    private final UUID channelId;
    private final ChannelRepository channelRepository;
    private final TelegramProperties properties;
    private final TelegramAuthorizationManager authorizationManager;
    private final ObjectProvider<TelegramClient> telegramClientProvider;

    private volatile UpdateAuthorizationState authorizationStateHandler;

    public TelegramAuthorizationStateListener(
            UUID channelId,
            ChannelRepository channelRepository,
            TelegramProperties properties,
            TelegramAuthorizationManager authorizationManager,
            ObjectProvider<TelegramClient> telegramClientProvider
    ) {
        this.channelId = channelId;
        this.channelRepository = channelRepository;
        this.properties = properties;
        this.authorizationManager = authorizationManager;
        this.telegramClientProvider = telegramClientProvider;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateAuthorizationState notification
    ) {
        log.debug(
                "TelegramAuthorizationStateListener received: {}",
                notification.authorizationState == null
                        ? "null"
                        : notification.authorizationState.getClass().getSimpleName()
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
                "Telegram auth state mapped: channelId={}, tdlibState={}, connectionStatus={}",
                channelId,
                state.getClass().getSimpleName(),
                status
        );
        updateStatus(status, state);
    }

    @Override
    public Class<TdApi.UpdateAuthorizationState> notificationType() {
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

        log.debug(
                "Telegram account loaded: channelAccountId={}, channelFound={}",
                channel.getAccount().getId(),
                true
        );

        if (channel.getStatus() == status) {
            log.debug(
                    "Telegram status already set: channelAccountId={}, currentStatus={}, requestedStatus={}",
                    channel.getAccount().getId(),
                    channel.getStatus(),
                    status
            );
            return;
        }

        log.debug(
                "Telegram saving connection status: channelAccountId={}, status={}",
                channel.getAccount().getId(),
                status
        );

        channel.setStatus(status);

        channelRepository.save(channel);

        log.debug(
                "Telegram connection status saved: channelAccountId={}, status={}, tdlibState={}",
                channel.getAccount().getId(),
                channel.getStatus(),
                state.getClass().getSimpleName()
        );
    }
}