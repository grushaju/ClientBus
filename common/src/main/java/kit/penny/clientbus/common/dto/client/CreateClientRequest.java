package kit.penny.clientbus.common.dto.client;

import java.util.List;
import java.util.UUID;

public record CreateClientRequest(
        UUID workspaceId,
        String firstName,
        String lastName,
        List<String> phoneList
) {
}
