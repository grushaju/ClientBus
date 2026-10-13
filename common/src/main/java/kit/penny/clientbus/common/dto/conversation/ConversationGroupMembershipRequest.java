
package kit.penny.clientbus.common.dto.conversation;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConversationGroupMembershipRequest(
        @NotNull UUID conversationId
) {
}
