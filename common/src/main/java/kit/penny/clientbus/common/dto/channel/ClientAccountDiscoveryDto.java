package kit.penny.clientbus.common.dto.channel;

import java.util.UUID;

public record ClientAccountDiscoveryDto (
    String externalId,
    String username,
    String phone,
    String displayName,
    UUID existingClientAccountId,
    ClientAccountAvatarPreviewDto avatarPreview
) {
    public ClientAccountDiscoveryDto(String externalId, String username, String phone, String displayName,
                                     UUID existingClientAccountId) {
        this(externalId, username, phone, displayName, existingClientAccountId, null);
    }
}

