package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.service.ChannelSendRequest;
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

    public ChannelSendRequest toRequest(
            OutboundMessageKafkaCommand command
    ) {
        return new ChannelSendRequest(
                command.messageId(),
                command.channelAccountId(),
                command.recipientExternalId(),
                command.type(),
                command.content(),
                mapAttachments(command)
        );
    }

    private List<ChannelAttachment> mapAttachments(
            OutboundMessageKafkaCommand command
    ) {
        if (command.attachments() == null
                || command.attachments().isEmpty()) {
            return List.of();
        }

        return command.attachments()
                .stream()
                .map(attachment ->
                        new ChannelAttachment(
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