package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationGroupEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationGroupMembershipEntity;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupMembershipRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationGroupServiceTest {

    @Mock
    private ConversationGroupRepository groupRepository;

    @Mock
    private ConversationGroupMembershipRepository membershipRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ConversationMapper conversationMapper;

    private ConversationGroupService service;

    @BeforeEach
    void setUp() {
        service = new ConversationGroupService(
                groupRepository,
                membershipRepository,
                conversationRepository,
                workspaceRepository,
                currentUserService,
                conversationMapper
        );
    }

    @Test
    void getGroupConversationsReturnsAccessibleConversations() {
        UUID workspaceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        ConversationGroupEntity group = mock(ConversationGroupEntity.class);
        var employee = mock(
                EmployeeEntity.class
        );

        when(group.getId()).thenReturn(groupId);
        when(group.getOwnerEmployee()).thenReturn(employee);
        when(employee.getId()).thenReturn(employeeId);

        ConversationGroupMembershipEntity membership =
                mock(ConversationGroupMembershipEntity.class);
        ConversationEntity conversation = mock(ConversationEntity.class);
        ConversationDto expectedDto = mock(ConversationDto.class);

        when(membershipRepository.findAccessibleMemberships(
                groupId,
                workspaceId,
                employeeId
        )).thenReturn(List.of(membership));

        when(membership.getConversation()).thenReturn(conversation);
        when(conversationMapper.toDto(conversation)).thenReturn(expectedDto);

        // Stub the access checks performed by requireOwnedGroup(...)
        when(currentUserService.isEmployee()).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);

        when(groupRepository.findByIdAndWorkspaceIdAndOwnerEmployeeId(
                groupId,
                workspaceId,
                employeeId
        )).thenReturn(java.util.Optional.of(group));

        List<ConversationDto> result =
                service.getGroupConversations(workspaceId, groupId);

        assertEquals(List.of(expectedDto), result);

        verify(membershipRepository).findAccessibleMemberships(
                groupId,
                workspaceId,
                employeeId
        );
        verify(conversationMapper).toDto(conversation);
    }

    @Test
    void getGroupConversationsReturnsEmptyListWhenNoAccessibleMemberships() {
        UUID workspaceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        ConversationGroupEntity group = mock(ConversationGroupEntity.class);
        var employee = mock(
                EmployeeEntity.class
        );

        when(group.getId()).thenReturn(groupId);
        when(group.getOwnerEmployee()).thenReturn(employee);
        when(employee.getId()).thenReturn(employeeId);

        when(currentUserService.isEmployee()).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);

        when(groupRepository.findByIdAndWorkspaceIdAndOwnerEmployeeId(
                groupId,
                workspaceId,
                employeeId
        )).thenReturn(java.util.Optional.of(group));

        when(membershipRepository.findAccessibleMemberships(
                groupId,
                workspaceId,
                employeeId
        )).thenReturn(List.of());

        List<ConversationDto> result =
                service.getGroupConversations(workspaceId, groupId);

        assertEquals(List.of(), result);
        verifyNoInteractions(conversationMapper);
    }

    @Test
    void superAdminCannotCreatePersonalGroups() {
        assertThrows(AccessDeniedException.class, () -> service.createGroup(UUID.randomUUID(), "Group", "users.svg"));
        verifyNoInteractions(groupRepository, membershipRepository, workspaceRepository);
        verify(currentUserService, never()).getCurrentEmployee();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "rename", "delete", "add", "remove"})
    void everyGroupOperationRequiresTheOwnerInTheRequestedWorkspace(String operation) {
        UUID workspaceId = UUID.randomUUID(), groupId = UUID.randomUUID(), employeeId = UUID.randomUUID();
        when(currentUserService.isEmployee()).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
        // An inaccessible owner/workspace combination has no matching group.
        when(groupRepository.findByIdAndWorkspaceIdAndOwnerEmployeeId(groupId, workspaceId, employeeId))
                .thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> {
            switch (operation) {
                case "read" -> service.getGroupConversations(workspaceId, groupId);
                case "rename" -> service.renameGroup(workspaceId, groupId, "New");
                case "delete" -> service.deleteGroup(workspaceId, groupId);
                case "add" -> service.addConversation(workspaceId, groupId, UUID.randomUUID());
                case "remove" -> service.removeConversation(workspaceId, groupId, UUID.randomUUID());
            }
        });
        verify(currentUserService).requireWorkspaceAccess(workspaceId);
        verifyNoInteractions(conversationRepository, membershipRepository);
        verify(groupRepository, never()).delete(any());
        verify(groupRepository, never()).saveAndFlush(any());
    }

    @Test
    void workspaceDenialStopsGroupAccessBeforeRepositoryLookup() {
        UUID workspaceId = UUID.randomUUID();
        doThrow(new AccessDeniedException("No workspace access")).when(currentUserService).requireWorkspaceAccess(workspaceId);
        assertThrows(AccessDeniedException.class, () -> service.getGroups(workspaceId));
        verifyNoInteractions(groupRepository, membershipRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void membershipCommandsRejectConversationsFromAnotherWorkspace(boolean remove) {
        UUID workspaceId = UUID.randomUUID(), groupId = UUID.randomUUID(), employeeId = UUID.randomUUID(), conversationId = UUID.randomUUID();
        when(currentUserService.isEmployee()).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
        ConversationGroupEntity group = new ConversationGroupEntity();
        group.setId(groupId);
        when(groupRepository.findByIdAndWorkspaceIdAndOwnerEmployeeId(groupId, workspaceId, employeeId)).thenReturn(Optional.of(group));
        WorkspaceEntity other = new WorkspaceEntity();
        other.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setWorkspace(other);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        assertThrows(EntityNotFoundException.class, () -> {
            if (remove) service.removeConversation(workspaceId, groupId, conversationId);
            else service.addConversation(workspaceId, groupId, conversationId);
        });
        verifyNoInteractions(membershipRepository);
    }
}
