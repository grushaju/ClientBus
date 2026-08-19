package kit.penny.clientbus.common.dto.auth;

public record LoginResponse(
        String accessToken,
        String tokenType
) {
}