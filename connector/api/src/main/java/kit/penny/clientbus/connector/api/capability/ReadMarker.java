package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand;

/** Capability: report an external read watermark for inbound messages. */
public interface ReadMarker {

    void handle(
            MarkMessagesReadCommand command
    );

    /** One bounded unit of work; the host must durably enqueue a non-null continuation. */
    default MarkMessagesReadCommand continueRead(MarkMessagesReadCommand command) {
        handle(command);
        return null;
    }
}
