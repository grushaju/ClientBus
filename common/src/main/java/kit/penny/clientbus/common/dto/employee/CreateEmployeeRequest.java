package kit.penny.clientbus.common.dto.employee;

import java.util.UUID;

public record CreateEmployeeRequest(
        UUID workspaceId,
        String login,
        String password,
        String firstName,
        String lastName,
        String phone,
        String email
) {
}