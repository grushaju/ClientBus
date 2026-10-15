
package kit.penny.clientbus.common.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationGroupRequest(
        @NotBlank
        @Size(max = 100)
        String name,
        String icon
) {
}
