package kit.penny.clientbus.common.dto.clientaccount;

import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public record ClientAccountDto(
        UUID id,
        UUID clientId,
        ChannelType channelType,
        String externalId,
        String username,
        String phone,
        String displayName
) {
}