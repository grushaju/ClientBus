package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.model.ConnectorSendResult;
import kit.penny.clientbus.connector.api.command.SendMessageCommand;

/** Capability: deliver an outbound message to an existing external conversation. */
public interface MessageSender {

    ConnectorSendResult handle(
            SendMessageCommand command
    );
}
