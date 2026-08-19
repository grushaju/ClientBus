package kit.penny.clientbus.common.dto.user;

import java.util.UUID;

public record UserDto(
        UUID id,
        String login,
        boolean enabled
) {
}
