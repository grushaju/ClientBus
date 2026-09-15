package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.service.MessageService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramMessageReadListenerTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ConversationRepository conversationRepository;
    private MessageService messageService;

    private TelegramMessageReadListener listener;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageService = mock(MessageService.class);

        listener = new TelegramMessageReadListener(
                CHANNEL_ACCOUNT_ID,
                conversationRepository,
                messageService
        );
    }

    @Test
    void notificationType_returnsUpdateChatReadOutbox() {
        assertThat(listener.notificationType())
                .isEqualTo(TdApi.UpdateChatReadOutbox.class);
    }

    @Test
    void nullNotification_isIgnored() {
        listener.handleNotification(null);

        verifyNoInteractions(
                conversationRepository,
                messageService
        );
    }

    @Test
    void invalidChatId_isIgnored() {
        TdApi.UpdateChatReadOutbox notification =
                new TdApi.UpdateChatReadOutbox(0, 105);

        listener.handleNotification(notification);

        verifyNoInteractions(
                conversationRepository,
                messageService
        );
    }

    @Test
    void invalidWatermark_isIgnored() {
        TdApi.UpdateChatReadOutbox notification =
                new TdApi.UpdateChatReadOutbox(123, 0);

        listener.handleNotification(notification);

        verifyNoInteractions(
                conversationRepository,
                messageService
        );
    }

    @Test
    void unknownConversation_isIgnored() {
        when(conversationRepository
                .findByChannelAccountIdAndClientAccountExternalId(
                        CHANNEL_ACCOUNT_ID,
                        "123"
                ))
                .thenReturn(Optional.empty());

        TdApi.UpdateChatReadOutbox notification =
                new TdApi.UpdateChatReadOutbox(123, 105);

        listener.handleNotification(notification);

        verify(conversationRepository)
                .findByChannelAccountIdAndClientAccountExternalId(
                        CHANNEL_ACCOUNT_ID,
                        "123"
                );

        verifyNoInteractions(messageService);
    }

    @Test
    void knownConversation_marksMessagesReadUpToWatermark() {
        UUID conversationId =
                UUID.fromString(
                        "22222222-2222-2222-2222-222222222222"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        when(conversationRepository
                .findByChannelAccountIdAndClientAccountExternalId(
                        CHANNEL_ACCOUNT_ID,
                        "123"
                ))
                .thenReturn(Optional.of(conversation));

        TdApi.UpdateChatReadOutbox notification =
                new TdApi.UpdateChatReadOutbox(123, 105);

        listener.handleNotification(notification);

        verify(conversationRepository)
                .findByChannelAccountIdAndClientAccountExternalId(
                        CHANNEL_ACCOUNT_ID,
                        "123"
                );

        verify(messageService)
                .markReadUpTo(
                        conversationId,
                        105
                );
    }
}
