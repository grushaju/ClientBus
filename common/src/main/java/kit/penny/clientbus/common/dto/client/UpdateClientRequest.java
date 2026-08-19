package kit.penny.clientbus.common.dto.client;

import java.util.List;

public record UpdateClientRequest(
        String firstName,
        String lastName,
        List<String> phoneList,
        Boolean enabled
) {
}