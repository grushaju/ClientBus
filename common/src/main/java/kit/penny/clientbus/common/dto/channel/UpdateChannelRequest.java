package kit.penny.clientbus.common.dto.channel;

import jakarta.validation.constraints.NotBlank;

public record UpdateChannelRequest(

        @NotBlank
        String name
) {
}
