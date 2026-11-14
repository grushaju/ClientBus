package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.server.service.ChannelStatusService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Host side of the channel status port delegating to the coalescing status service. */
@Component
public class ChannelStatusHostAdapter
        implements HostChannelStatus {

    private final ChannelStatusService channelStatus;

    public ChannelStatusHostAdapter(
            ChannelStatusService channelStatus
    ) {
        this.channelStatus = channelStatus;
    }

    @Override
    public void expectDisconnect(
            UUID channelAccountId,
            ChannelConnectionStatus expectedStatus
    ) {
        channelStatus.expectDisconnect(
                channelAccountId,
                expectedStatus
        );
    }

    @Override
    public void beginConnecting(
            UUID channelAccountId
    ) {
        channelStatus.beginConnecting(
                channelAccountId
        );
    }

    @Override
    public boolean transition(
            UUID channelAccountId,
            ChannelConnectionStatus status,
            String reason
    ) {
        return channelStatus.transition(
                channelAccountId,
                status,
                reason
        );
    }
}
