package kit.penny.clientbus.server.connector;

public record ClientAccountProfile(
        String username,
        String phone,
        String displayName,
        String avatarUrl
) {
}
