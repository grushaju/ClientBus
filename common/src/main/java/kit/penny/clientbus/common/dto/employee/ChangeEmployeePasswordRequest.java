package kit.penny.clientbus.common.dto.employee;

import jakarta.validation.constraints.NotBlank;

public record ChangeEmployeePasswordRequest(

        @NotBlank
        String currentPassword,

        @NotBlank
        String newPassword
) {
}