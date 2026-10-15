
package kit.penny.clientbus.common.dto.conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationGroupDto(
        UUID id,
        UUID workspaceId,
        UUID ownerEmployeeId,
        String name,
        String icon,
        Instant createdAt,
        Instant updatedAt
) {
}
