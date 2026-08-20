package kit.penny.clientbus.common.dto.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.ChannelType;

public record AddClientAccountRequest(

        @NotNull
        ChannelType channelType,

        @NotBlank
        String externalId,

        String username,

        String phone,

        String displayName
) {
}