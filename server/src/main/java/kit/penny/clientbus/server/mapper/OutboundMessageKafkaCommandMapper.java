package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.connector.api.command.SendMessageCommand;
import kit.penny.clientbus.connector.api.model.ConnectorAttachment;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboundMessageKafkaCommandMapper {

    private final IAttachmentStorage attachmentStorage;

    public OutboundMessageKafkaCommandMapper(
            IAttachmentStorage attachmentStorage
    ) {
        this.attachmentStorage = attachmentStorage;
    }

    public SendMessageCommand toCommand(
            OutboundMessageKafkaCommand command
    ) {
        return new SendMessageCommand(
                command.messageId(),
                command.channelAccountId(),
                command.recipientExternalId(),
                command.type(),
                command.content(),
                mapAttachments(command),
                command.sendAttemptId()
        );
    }

    private List<ConnectorAttachment> mapAttachments(
            OutboundMessageKafkaCommand command
    ) {
        if (command.attachments() == null
                || command.attachments().isEmpty()) {
            return List.of();
        }

        return command.attachments()
                .stream()
                .map(attachment ->
                        new ConnectorAttachment(
                                attachment.type(),
                                attachment.fileName(),
                                attachment.contentType(),
                                attachment.size(),
                                attachmentStorage.load(
                                        attachment.storageKey()
                                )
                        )
                )
                .toList();
    }
}
