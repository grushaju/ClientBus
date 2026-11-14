package kit.penny.clientbus.connector.api.host;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

/**
 * Immutable view of a channel account needed by connector implementations. Entity graphs,
 * JPA types and access decisions stay with the host.
 */
public record HostChannelAccountSnapshot(

        UUID id,

        UUID channelId,

        UUID workspaceId,

        ChannelType channelType,

        ChannelConnectionStatus channelStatus,

        String phone,

        String externalId

) {
}
