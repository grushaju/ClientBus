package kit.penny.clientbus.common.dto.clientaccount;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

import kit.penny.clientbus.common.enums.ChannelType;

public record CreateClientAccountRequest(

        UUID clientId,

        @NotNull
        ChannelType channelType,

        @NotBlank
        String externalId,

        String username,

        String phone,

        String displayName
) {
}