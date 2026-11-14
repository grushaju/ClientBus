package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand;

/** Capability: report an external read watermark for inbound messages. */
public interface ReadMarker {

    void handle(
            MarkMessagesReadCommand command
    );
}
