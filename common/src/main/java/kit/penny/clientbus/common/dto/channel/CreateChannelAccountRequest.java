package kit.penny.clientbus.common.dto.channel;

import jakarta.validation.constraints.NotBlank;

public record CreateChannelAccountRequest(

        @NotBlank
        String externalId,

        String username,

        String phone,

        String displayName
) {
}