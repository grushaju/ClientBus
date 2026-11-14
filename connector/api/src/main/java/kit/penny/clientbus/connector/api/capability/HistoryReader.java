package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.model.SyncConversationHistoryResult;
import kit.penny.clientbus.connector.api.command.SyncConversationHistoryCommand;

/** Capability: read a page of external conversation history. */
public interface HistoryReader {

    SyncConversationHistoryResult handle(
            SyncConversationHistoryCommand command
    );
}
