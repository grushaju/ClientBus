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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
