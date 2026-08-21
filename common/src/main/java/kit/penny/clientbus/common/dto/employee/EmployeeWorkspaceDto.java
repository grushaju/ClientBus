package kit.penny.clientbus.common.dto.employee;

import java.util.UUID;

public record EmployeeWorkspaceDto(
        UUID employeeId,
        UUID workspaceId
) {
}