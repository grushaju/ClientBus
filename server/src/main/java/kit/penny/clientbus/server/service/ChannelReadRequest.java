package kit.penny.clientbus.server.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChannelReadRequest(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String recipientExternalId,

        @NotBlank
        String lastReadExternalId

) {
}