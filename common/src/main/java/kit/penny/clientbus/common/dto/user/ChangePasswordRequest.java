package kit.penny.clientbus.common.dto.user;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {
}