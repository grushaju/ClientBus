package kit.penny.clientbus.common.dto.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClientDto(
        UUID id,
        UUID organizationId,
        String firstName,
        String lastName,
        List<String> phoneList,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String avatarUrl
) {
    public ClientDto(
            UUID id,
            UUID organizationId,
            String firstName,
            String lastName,
            List<String> phoneList,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                organizationId,
                firstName,
                lastName,
                phoneList,
                enabled,
                createdAt,
                updatedAt,
                null
        );
    }
}