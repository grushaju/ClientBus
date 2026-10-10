
package kit.penny.clientbus.common.dto.channel;

import java.util.UUID;

public record ChannelAccountDto(
        UUID id,
        String externalId,
        String username,
        String phone,
        String displayName,
        String avatarUrl
) {

    public ChannelAccountDto(
            UUID id,
            String externalId,
            String username,
            String phone,
            String displayName
    ) {
        this(
                id,
                externalId,
                username,
                phone,
                displayName,
                null
        );
    }
}