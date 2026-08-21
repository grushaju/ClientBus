package kit.penny.clientbus.common.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetEmployeePasswordRequest(

        @NotBlank(message = "Password must not be blank")
        @Size(
                min = 8,
                max = 100,
                message = "Password must be between 8 and 100 characters"
        )
        String newPassword

) {
}