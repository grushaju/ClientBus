package kit.penny.clientbus.common.dto.employee;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateEmployeeRequest(

        @NotNull
        UUID organizationId,

        @NotBlank
        String username,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String password,

        @NotBlank
        String firstName,

        @NotBlank
        String lastName,

        String phone
) {
}