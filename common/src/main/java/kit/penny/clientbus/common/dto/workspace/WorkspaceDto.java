package kit.penny.clientbus.common.dto.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceDto(
        UUID id,
        UUID organizationId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String avatarUrl
) {
    public WorkspaceDto(
            UUID id,
            UUID organizationId,
            String name,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, organizationId, name, createdAt, updatedAt, null);
    }
}