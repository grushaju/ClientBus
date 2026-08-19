package kit.penny.clientbus.common.dto.auth;

public record LoginRequest(
        String login,
        String password,
        String email
) {
}
