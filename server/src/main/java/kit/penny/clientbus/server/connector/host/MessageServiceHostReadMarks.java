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
            String dialogExternalId,
            String clientExternalId,
            long lastReadExternalId
    ) {
        ConversationEntity conversation =
                resolveConversation(
                        channelAccountId,
                        dialogExternalId,
                        clientExternalId
                );

        if (conversation == null) {
            log.debug(
                    "Ignoring Telegram read update: "
                            + "conversation not found, "
                            + "channelAccountId={}, dialogId={}, chatId={}",
                    channelAccountId,
                    dialogExternalId,
                    clientExternalId
            );

            return;
        }

        messageService.markReadUpTo(
                conversation.getId(),
                lastReadExternalId
        );
    }

    /*
     * 13.2b: сначала внешняя ссылка диалога; поиск по участнику — только при одном
     * кандидате. Неоднозначный контекст игнорируется (не угадываем). Легаси-строки
     * создавались без ссылки диалога, где чат и участник совпадали, поэтому при промахе
     * по ссылке тот же идентификатор пробзуется как id участника.
     */
    private ConversationEntity resolveConversation(
            UUID channelAccountId,
            String dialogExternalId,
            String clientExternalId
    ) {
        if (dialogExternalId != null
                && !dialogExternalId.isBlank()) {

            ConversationEntity byDialog =
                    conversationRepository
                            .findByChannelAccountIdAndDialogExternalId(
                                    channelAccountId,
                                    dialogExternalId
                            )
                            .orElse(null);

            if (byDialog != null) {
                return byDialog;
            }
        }

        String counterpartReference =
                clientExternalId != null
                        && !clientExternalId.isBlank()
                        ? clientExternalId
                        : dialogExternalId;

        if (counterpartReference == null
                || counterpartReference.isBlank()) {

            return null;
        }

        var candidates =
                conversationRepository
                        .findAllByChannelAccountIdAndClientAccountExternalId(
                                channelAccountId,
                                counterpartReference
                        );

        return candidates.size() == 1
                ? candidates.get(0)
                : null;
    }
}
