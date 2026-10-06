package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChannelSynchronizationListener
        implements IChannelListener {

    private final RecentChatsSyncCoordinator coordinator;

    public ChannelSynchronizationListener(
            RecentChatsSyncCoordinator coordinator
    ) {
        this.coordinator =
                coordinator;
    }

    @Override
    public void channelChanged(
            ChannelEvent event
    ) {
        if (event == null
                || event.status()
                != ChannelConnectionStatus.CONNECTED) {
            return;
        }

        UUID channelAccountId =
                event.channelAccountId();

        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        coordinator.request(
                channelAccountId
        );
    }
}