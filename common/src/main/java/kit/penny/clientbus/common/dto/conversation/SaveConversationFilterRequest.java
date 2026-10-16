
package kit.penny.clientbus.common.dto.conversation;

import kit.penny.clientbus.common.enums.SavedFilterType;

public record SaveConversationFilterRequest(
        String name,
        SavedFilterType filterType,
        ConversationSearchRequest filterDefinition
) {
}
