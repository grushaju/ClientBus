package kit.penny.clientbus.connector.api.event;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;

import java.util.UUID;

public record ChannelEvent(
        UUID channelAccountId,
        ChannelConnectionStatus status
) {
}
