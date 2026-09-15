package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class TelegramMessageReadListener
        implements ITdlibUpdateListener<TdApi.UpdateChatReadOutbox> {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramMessageReadListener.class
            );

    private final UUID channelAccountId;
    private final ConversationRepository conversationRepository;
    private final MessageService messageService;

    public TelegramMessageReadListener(
            UUID channelAccountId,
            ConversationRepository conversationRepository,
            MessageService messageService
    ) {
        this.channelAccountId = channelAccountId;
        this.conversationRepository = conversationRepository;
        this.messageService = messageService;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateChatReadOutbox notification
    ) {
        if (notification == null) {
            return;
        }

        long chatId =
                notification.chatId;

        long lastReadOutboxMessageId =
                notification.lastReadOutboxMessageId;

        if (chatId == 0
                || lastReadOutboxMessageId <= 0) {

            return;
        }

        String clientExternalId =
                Long.toString(chatId);

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
                    chatId
            );

            return;
        }

        messageService.markReadUpTo(
                conversation.getId(),
                lastReadOutboxMessageId
        );
    }

    @Override
    public Class<TdApi.UpdateChatReadOutbox>
    notificationType() {
        return TdApi.UpdateChatReadOutbox.class;
    }
}