package kit.penny.clientbus.common.dto.client;

import java.util.List;

public record CreateClientRequest(
        String firstName,
        String lastName,
        List<String> phoneList
) {
}
