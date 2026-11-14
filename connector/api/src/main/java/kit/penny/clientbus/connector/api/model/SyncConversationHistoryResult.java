package kit.penny.clientbus.connector.api.model;

import java.util.List;

public record SyncConversationHistoryResult(
        List<ConnectorInboundMessage> messages,
        boolean historyStartReached
) {
}
