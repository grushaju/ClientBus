package kit.penny.clientbus.common.dto.channel;

import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public record ChannelDto(
        UUID id,
        UUID workspaceId,
        ChannelType type,
        String name,
        ChannelAccountDto account
) {
}