package kit.penny.clientbus.common.dto.organization;

import jakarta.validation.constraints.NotBlank;

public record UpdateOrganizationRequest(

        @NotBlank
        String name
) {
}