package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 13.2b: dialog-reference-first resolution with an unambiguous counterpart fallback.
 */
class MessageServiceHostReadMarksTest {

    private ConversationRepository conversationRepository;
    private MessageService messageService;
    private MessageServiceHostReadMarks readMarks;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        conversationRepository =
                mock(ConversationRepository.class);

        messageService =
                mock(MessageService.class);

        readMarks =
                new MessageServiceHostReadMarks(
                        conversationRepository,
                        messageService
                );

        channelAccountId =
                UUID.randomUUID();
    }

    @Test
    void dialogReferenceResolvesConversationDirectly() {

        UUID conversationId = UUID.randomUUID();

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        when(conversationRepository
                .findByChannelAccountIdAndDialogExternalId(
                        channelAccountId,
                        "42"
                ))
                .thenReturn(Optional.of(conversation));

        readMarks.markReadUpTo(
                channelAccountId,
                "42",
                null,
                105L
        );

        verify(messageService)
                .markReadUpTo(conversationId, 105L);

        verify(conversationRepository, never())
                .findAllByChannelAccountIdAndClientAccountExternalId(
                        any(),
                        any()
                );
    }

    @Test
    void counterpartFallbackAppliesOnlyWhenUnambiguous() {

        UUID conversationId = UUID.randomUUID();

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        when(conversationRepository
                .findAllByChannelAccountIdAndClientAccountExternalId(
                        channelAccountId,
                        "99"
                ))
                .thenReturn(List.of(conversation));

        readMarks.markReadUpTo(
                channelAccountId,
                null,
                "99",
                7L
        );

        verify(messageService)
                .markReadUpTo(conversationId, 7L);
    }

    @Test
    void ambiguousCounterpartContextIsNotApplied() {

        when(conversationRepository
                .findAllByChannelAccountIdAndClientAccountExternalId(
                        channelAccountId,
                        "99"
                ))
                .thenReturn(
                        List.of(
                                new ConversationEntity(),
                                new ConversationEntity()
                        )
                );

        readMarks.markReadUpTo(
                channelAccountId,
                null,
                "99",
                7L
        );

        verifyNoInteractions(messageService);
    }

    @Test
    void missingContextIsIgnored() {

        readMarks.markReadUpTo(
                channelAccountId,
                null,
                null,
                7L
        );

        verifyNoInteractions(messageService);

        verify(messageService, never())
                .markReadUpTo(any(), anyLong());
    }
}
