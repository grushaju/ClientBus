package kit.penny.clientbus.common.dto.user;

public record CreateUserRequest(
        String login,
        String password
) {
}