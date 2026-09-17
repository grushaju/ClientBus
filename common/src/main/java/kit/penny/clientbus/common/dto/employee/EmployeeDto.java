package kit.penny.clientbus.common.dto.employee;

import kit.penny.clientbus.common.enums.UserRole;

import java.time.Instant;
import java.util.UUID;

public record EmployeeDto(
        UUID id,
        UUID organizationId,
        String username,
        String email,
        String firstName,
        String lastName,
        String phone,
        UserRole role,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}