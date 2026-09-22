package kit.penny.clientbus.common.dto.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClientListItemDto(
        UUID id,
        String firstName,
        String lastName,
        List<String> phoneList,
        long accountCount,
        Instant lastContactAt,
        boolean enabled
) {
}
