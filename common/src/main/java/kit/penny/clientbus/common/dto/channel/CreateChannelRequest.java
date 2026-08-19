package kit.penny.clientbus.common.dto.channel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public record CreateChannelRequest(

        @NotNull
        UUID workspaceId,

        @NotNull
        ChannelType type,

        @NotBlank
        String name,

        @NotNull
        @Valid
        CreateChannelAccountRequest account
) {
}