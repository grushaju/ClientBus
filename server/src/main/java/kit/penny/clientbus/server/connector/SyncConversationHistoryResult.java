package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;

import java.util.List;

public record SyncConversationHistoryResult(
        List<PlatformMessageRequest> messages,
        boolean historyStartReached
) {
}