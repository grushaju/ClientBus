package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;

import java.util.UUID;

public record ChannelEvent(
        UUID channelAccountId,
        ChannelConnectionStatus status
) {
}