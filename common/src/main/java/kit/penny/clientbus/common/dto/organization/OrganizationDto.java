package kit.penny.clientbus.common.dto.organization;

import java.time.Instant;
import java.util.UUID;

public record OrganizationDto(
        UUID id,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}