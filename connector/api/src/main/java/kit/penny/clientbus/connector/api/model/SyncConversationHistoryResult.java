package kit.penny.clientbus.connector.api.model;

import java.util.List;

public record SyncConversationHistoryResult(
        List<ConnectorInboundMessage> messages,
        boolean historyStartReached,

        /**
         * 13.2b: why the page ended — "HISTORY_START_REACHED", "PAGE_COMPLETE" or a
         * connector-specific reason; nullable for legacy connectors that predate the field.
         */
        String endReason,
        kit.penny.clientbus.connector.api.host.HostConversationCheckpoints.Update checkpoint
) {

    public SyncConversationHistoryResult(List<ConnectorInboundMessage> messages, boolean historyStartReached, String endReason) {
        this(messages, historyStartReached, endReason, null);
    }

    public SyncConversationHistoryResult(
            List<ConnectorInboundMessage> messages,
            boolean historyStartReached
    ) {
        this(messages, historyStartReached, null);
    }
}
