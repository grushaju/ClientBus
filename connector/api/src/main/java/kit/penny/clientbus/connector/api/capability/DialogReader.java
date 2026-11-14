package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.model.ConnectorConversation;
import kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand;

import java.util.List;

/** Capability: enumerate recent conversations of a connected channel account. */
public interface DialogReader {

    List<ConnectorConversation> handle(
            SyncRecentChatsCommand command
    );
}
