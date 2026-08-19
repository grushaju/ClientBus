package kit.penny.clientbus.common.dto.employee;

import java.time.Instant;
import java.util.UUID;

public record EmployeeDto(
        UUID id,
        UUID workspaceId,
        UUID userId,
        String login,
        String firstName,
        String lastName,
        String phone,
        String email,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}