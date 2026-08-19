package kit.penny.clientbus.common.dto.employee;

import jakarta.validation.constraints.Email;

public record UpdateEmployeeCredentialsRequest(

        String username,

        @Email
        String email
) {
}