package kit.penny.clientbus.server.service;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ConversationServiceOrderingTest {


    @InjectMocks
    private ConversationService conversationService;

    private ConversationEntity conversation;

    @BeforeEach
    void setUp() {

        conversation =
                new ConversationEntity();

        conversation.setLastMessageAt(
                Instant.parse(
                        "2026-09-30T10:00:00Z"
                )
        );

        conversation.setLastMessagePreview(
                "New message"
        );
    }

    @Test
    void olderMessage_shouldNotRegressLastMessage() {

        Instant oldMessageTime =
                Instant.parse(
                        "2026-09-30T09:00:00Z"
                );

        conversationService.updateLastMessage(
                conversation,
                oldMessageTime,
                "Old message"
        );

        assertThat(
                conversation.getLastMessageAt()
        )
                .isEqualTo(
                        Instant.parse(
                                "2026-09-30T10:00:00Z"
                        )
                );

        assertThat(
                conversation.getLastMessagePreview()
        )
                .isEqualTo(
                        "New message"
                );
    }

    @Test
    void newerMessage_shouldUpdateLastMessage() {

        Instant newerMessageTime =
                Instant.parse(
                        "2026-09-30T11:00:00Z"
                );

        conversationService.updateLastMessage(
                conversation,
                newerMessageTime,
                "Newer message"
        );

        assertThat(
                conversation.getLastMessageAt()
        )
                .isEqualTo(
                        newerMessageTime
                );

        assertThat(
                conversation.getLastMessagePreview()
        )
                .isEqualTo(
                        "Newer message"
                );
    }

    @Test
    void firstMessage_shouldInitializeLastMessage() {

        ConversationEntity emptyConversation =
                new ConversationEntity();

        Instant messageTime =
                Instant.parse(
                        "2026-09-30T10:00:00Z"
                );

        conversationService.updateLastMessage(
                emptyConversation,
                messageTime,
                "First message"
        );

        assertThat(
                emptyConversation.getLastMessageAt()
        )
                .isEqualTo(
                        messageTime
                );

        assertThat(
                emptyConversation.getLastMessagePreview()
        )
                .isEqualTo(
                        "First message"
                );
    }

    @Test
    void equalMessageTime_shouldUpdatePreview() {

        Instant messageTime =
                Instant.parse(
                        "2026-09-30T10:00:00Z"
                );

        conversationService.updateLastMessage(
                conversation,
                messageTime,
                "Same timestamp message"
        );

        assertThat(
                conversation.getLastMessageAt()
        )
                .isEqualTo(
                        messageTime
                );

        assertThat(
                conversation.getLastMessagePreview()
        )
                .isEqualTo(
                        "Same timestamp message"
                );
    }

    @Test
    void oversizedPreview_shouldBeLimitedToColumnLength() {

        conversationService.updateLastMessage(
                conversation,
                Instant.parse(
                        "2026-09-30T11:00:00Z"
                ),
                "д".repeat(
                        4_000
                )
        );

        assertThat(
                conversation.getLastMessagePreview()
        )
                .hasSize(
                        500
                )
                .endsWith(
                        "…"
                );
    }

    @Test
    void previewAtColumnLimit_shouldBeStoredUntouched() {

        String exact = "д".repeat(
                500
        );

        conversationService.updateLastMessage(
                conversation,
                Instant.parse(
                        "2026-09-30T11:00:00Z"
                ),
                exact
        );

        assertThat(
                conversation.getLastMessagePreview()
        )
                .isEqualTo(
                        exact
                );
    }

    @Test
    void surrogatePairAtLimitBoundary_shouldNotBeSplit() {

        conversationService.updateLastMessage(
                conversation,
                Instant.parse(
                        "2026-09-30T11:00:00Z"
                ),
                "д".repeat(
                        499
                ) + "😀" + "хвост"
        );

        String preview =
                conversation.getLastMessagePreview();

        assertThat(
                Character.isHighSurrogate(
                        preview.charAt(
                                preview.length() - 2
                        )
                )
        )
                .isFalse();

        assertThat(
                preview
        )
                .doesNotContain(
                        "\uD83D"
                )
                .endsWith(
                        "…"
                );
    }
}