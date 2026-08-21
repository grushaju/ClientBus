package kit.penny.clientbus.common.dto.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateWorkspaceRequest(

        @NotNull
        UUID organizationId,

        @NotBlank
        String name
) {
}