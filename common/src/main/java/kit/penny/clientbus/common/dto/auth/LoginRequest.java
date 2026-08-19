package kit.penny.clientbus.common.dto.auth;

public record LoginRequest(
        String username,
        String password,
        String email
) {
}
