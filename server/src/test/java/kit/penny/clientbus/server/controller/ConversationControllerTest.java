package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationDto conversationDto;

    private ConversationController controller;

    private UUID conversationId;
    private UUID channelAccountId;
    private UUID clientAccountId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        controller = new ConversationController(
                conversationService
        );

        conversationId = UUID.randomUUID();
        channelAccountId = UUID.randomUUID();
        clientAccountId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
    }

    @Test
    void getConversation_returnsConversation() {

        when(conversationService.getConversation(
                conversationId
        )).thenReturn(conversationDto);

        ResponseEntity<ConversationDto> response =
                controller.getConversation(
                        conversationId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                conversationDto,
                response.getBody()
        );

        verify(conversationService)
                .getConversation(conversationId);
    }

    @Test
    void getChannelAccountConversations_returnsConversations() {

        List<ConversationDto> conversations =
                List.of(conversationDto);

        when(conversationService
                .getChannelAccountConversations(
                        channelAccountId
                ))
                .thenReturn(conversations);

        ResponseEntity<List<ConversationDto>> response =
                controller.getChannelAccountConversations(
                        channelAccountId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                conversations,
                response.getBody()
        );

        verify(conversationService)
                .getChannelAccountConversations(
                        channelAccountId
                );
    }

    @Test
    void getClientAccountConversations_returnsConversations() {

        List<ConversationDto> conversations =
                List.of(conversationDto);

        when(conversationService
                .getClientAccountConversations(
                        clientAccountId
                ))
                .thenReturn(conversations);

        ResponseEntity<List<ConversationDto>> response =
                controller.getClientAccountConversations(
                        clientAccountId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                conversations,
                response.getBody()
        );

        verify(conversationService)
                .getClientAccountConversations(
                        clientAccountId
                );
    }

    @Test
    void getWorkspaceUnreadCount_returnsCount() {

        when(conversationService
                .getWorkspaceUnreadCount(workspaceId))
                .thenReturn(3L);

        ResponseEntity<Long> response =
                controller.getWorkspaceUnreadCount(
                        workspaceId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertEquals(
                3L,
                response.getBody()
        );

        verify(conversationService)
                .getWorkspaceUnreadCount(workspaceId);
    }

    @Test
    void markAsRead_returnsConversation() {

        when(conversationService
                .markAsRead(conversationId))
                .thenReturn(conversationDto);

        ResponseEntity<ConversationDto> response =
                controller.markAsRead(
                        conversationId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                conversationDto,
                response.getBody()
        );

        verify(conversationService)
                .markAsRead(conversationId);
    }
}