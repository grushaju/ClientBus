package kit.penny.clientbus.common.dto.channel;

public record UpdateChannelAccountRequest(
        String username,
        String phone,
        String displayName
) {
}
