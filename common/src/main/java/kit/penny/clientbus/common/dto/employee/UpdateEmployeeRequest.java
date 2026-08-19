package kit.penny.clientbus.common.dto.employee;

public record UpdateEmployeeRequest(
        String firstName,
        String lastName,
        String phone,
        String email,
        Boolean enabled
) {
}