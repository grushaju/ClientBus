package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostMessageReadMarks;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Host side of the read watermark port: conversation lookup plus the transactional service call. */
@Component
public class MessageServiceHostReadMarks
        implements HostMessageReadMarks {

    private static final Logger log =
            LoggerFactory.getLogger(
                    MessageServiceHostReadMarks.class
            );

    private final ConversationRepository conversationRepository;
    private final MessageService messageService;

    public MessageServiceHostReadMarks(
            ConversationRepository conversationRepository,
            MessageService messageService
    ) {
        this.conversationRepository = conversationRepository;
        this.messageService = messageService;
    }

    @Override
    public void markReadUpTo(
            UUID channelAccountId,
            String clientExternalId,
            long lastReadExternalId
    ) {
        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountExternalId(
                                channelAccountId,
                                clientExternalId
                        )
                        .orElse(null);

        if (conversation == null) {
            log.debug(
                    "Ignoring Telegram read update: "
                            + "conversation not found, "
                            + "channelAccountId={}, chatId={}",
                    channelAccountId,
                    clientExternalId
            );

            return;
        }

        messageService.markReadUpTo(
                conversation.getId(),
                lastReadExternalId
        );
    }
}
