package kit.penny.clientbus.connector.api.host;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;

import java.util.UUID;

/**
 * Host port for channel account connection status transitions started by connector code.
 * Expectations (planned disconnects), coalescing and UI publication stay with the host service.
 */
public interface HostChannelStatus {

    void expectDisconnect(
            UUID channelAccountId,
            ChannelConnectionStatus expectedStatus
    );

    void beginConnecting(
            UUID channelAccountId
    );

    boolean transition(
            UUID channelAccountId,
            ChannelConnectionStatus status,
            String reason
    );
}
