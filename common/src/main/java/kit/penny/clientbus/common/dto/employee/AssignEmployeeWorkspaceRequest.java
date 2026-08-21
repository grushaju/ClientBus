package kit.penny.clientbus.common.dto.employee;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignEmployeeWorkspaceRequest(

        @NotNull
        UUID workspaceId
) {
}