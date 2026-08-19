package kit.penny.clientbus.common.dto.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClientDto(
        UUID id,
        UUID workspaceId,
        String firstName,
        String lastName,
        List<String> phoneList,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}