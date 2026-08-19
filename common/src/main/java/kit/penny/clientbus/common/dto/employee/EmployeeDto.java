package kit.penny.clientbus.common.dto.employee;

import java.time.Instant;
import java.util.UUID;

public record EmployeeDto(
        UUID id,
        UUID workspaceId,
        String username,
        String email,
        String firstName,
        String lastName,
        String phone,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}