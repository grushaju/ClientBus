package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.service.ConversationGroupService;
import kit.penny.clientbus.server.service.ConversationService;

import kit.penny.clientbus.common.dto.conversation.ConversationGroupDto;
import kit.penny.clientbus.common.dto.conversation.ConversationGroupMembershipRequest;
import kit.penny.clientbus.common.dto.conversation.CreateConversationGroupRequest;
import kit.penny.clientbus.common.dto.conversation.RenameConversationGroupRequest;

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
    private ConversationGroupService conversationGroupService;

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
                conversationService,
                conversationGroupService,
                mock(kit.penny.clientbus.server.service.OutboundConversationService.class)
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


    @Test
    void getGroups_returnsGroups() {
        ConversationGroupDto group = new ConversationGroupDto(
                UUID.randomUUID(),
                workspaceId,
                UUID.randomUUID(),
                "Важные клиенты",
                null,
                null,
                null
        );
        List<ConversationGroupDto> groups = List.of(group);

        when(conversationGroupService.getGroups(workspaceId))
                .thenReturn(groups);

        ResponseEntity<List<ConversationGroupDto>> response =
                controller.getGroups(workspaceId);

        assertEquals(200, response.getStatusCode().value());
        assertSame(groups, response.getBody());

        verify(conversationGroupService).getGroups(workspaceId);
    }

    @Test
    void createGroup_returnsCreatedGroup() {
        CreateConversationGroupRequest request =
                new CreateConversationGroupRequest("Важные клиенты", "heart");

        ConversationGroupDto group = new ConversationGroupDto(
                UUID.randomUUID(),
                workspaceId,
                UUID.randomUUID(),
                "Важные клиенты",
                null,
                null,
                null
        );

        when(conversationGroupService.createGroup(
                workspaceId,
                request.name(), request.icon()
        )).thenReturn(group);

        ResponseEntity<ConversationGroupDto> response =
                controller.createGroup(workspaceId, request);

        assertEquals(201, response.getStatusCode().value());
        assertSame(group, response.getBody());

        verify(conversationGroupService)
                .createGroup(workspaceId, request.name(), request.icon());
    }

    @Test
    void renameGroup_returnsUpdatedGroup() {
        UUID groupId = UUID.randomUUID();
        RenameConversationGroupRequest request =
                new RenameConversationGroupRequest("VIP", "star");

        ConversationGroupDto group = new ConversationGroupDto(
                groupId,
                workspaceId,
                UUID.randomUUID(),
                "VIP",
                null,
                null,
                null
        );

        when(conversationGroupService.renameGroup(
                workspaceId,
                groupId,
                request.name(), request.icon()
        )).thenReturn(group);

        ResponseEntity<ConversationGroupDto> response =
                controller.renameGroup(workspaceId, groupId, request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(group, response.getBody());

        verify(conversationGroupService)
                .renameGroup(workspaceId, groupId, request.name(), request.icon());
    }

    @Test
    void deleteGroup_returnsNoContent() {
        UUID groupId = UUID.randomUUID();

        ResponseEntity<Void> response =
                controller.deleteGroup(workspaceId, groupId);

        assertEquals(204, response.getStatusCode().value());
        verify(conversationGroupService).deleteGroup(workspaceId, groupId);
    }

    @Test
    void addConversationToGroup_returnsNoContent() {
        UUID groupId = UUID.randomUUID();
        ConversationGroupMembershipRequest request =
                new ConversationGroupMembershipRequest(conversationId);

        ResponseEntity<Void> response =
                controller.addConversationToGroup(
                        workspaceId,
                        groupId,
                        request
                );

        assertEquals(204, response.getStatusCode().value());

        verify(conversationGroupService).addConversation(
                workspaceId,
                groupId,
                conversationId
        );
    }

    @Test
    void removeConversationFromGroup_returnsNoContent() {
        UUID groupId = UUID.randomUUID();

        ResponseEntity<Void> response =
                controller.removeConversationFromGroup(
                        workspaceId,
                        groupId,
                        conversationId
                );

        assertEquals(204, response.getStatusCode().value());

        verify(conversationGroupService).removeConversation(
                workspaceId,
                groupId,
                conversationId
        );
    }

}
