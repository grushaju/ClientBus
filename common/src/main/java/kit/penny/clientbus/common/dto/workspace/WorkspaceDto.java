package kit.penny.clientbus.common.dto.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceDto(
        UUID id,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
