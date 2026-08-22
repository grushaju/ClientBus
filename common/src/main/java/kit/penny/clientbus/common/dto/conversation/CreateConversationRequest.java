package kit.penny.clientbus.common.dto.conversation;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateConversationRequest(

        @NotNull
        UUID workspaceId,

        @NotNull
        UUID channelAccountId,

        @NotNull
        UUID clientAccountId
) {
}