
package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceAssignmentTest {

    @Mock ConversationRepository conversationRepository;
    @Mock WorkspaceRepository workspaceRepository;
    @Mock ChannelAccountRepository channelAccountRepository;
    @Mock ClientAccountRepository clientAccountRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock EmployeeWorkspaceRepository employeeWorkspaceRepository;
    @Mock ConversationMapper conversationMapper;
    @Mock CurrentUserService currentUserService;
    @Mock MessageRepository messageRepository;
    @Mock kit.penny.clientbus.server.kafka.producer.IChannelReadPublisher channelReadPublisher;
    @Mock ConversationGroupRepository conversationGroupRepository;
    @Mock ConversationGroupMembershipRepository conversationGroupMembershipRepository;

    @InjectMocks ConversationService service;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    private ConversationEntity conversation() {
        OrganizationEntity organization =
                new OrganizationEntity("Org");
        organization.setId(organizationId);

        WorkspaceEntity workspace =
                new WorkspaceEntity(organization, "Workspace");
        workspace.setId(workspaceId);

        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setWorkspace(workspace);

        return conversation;
    }

    private EmployeeEntity employee() {
        OrganizationEntity organization =
                new OrganizationEntity("Org");
        organization.setId(organizationId);

        EmployeeEntity employee = new EmployeeEntity();
        employee.setId(employeeId);
        employee.setOrganization(organization);

        return employee;
    }

    private void givenConversation(ConversationEntity conversation) {
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
    }

    @Test
    void supervisorCanAssignEmployeeInOwnWorkspace() {
        ConversationEntity conversation = conversation();
        EmployeeEntity employee = employee();
        givenConversation(conversation);

        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(true);
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(true);

        service.assignEmployee(conversationId, employeeId);

        assertSame(employee, conversation.getAssignedEmployee());
    }

    @Test
    void supervisorCanUnassignConversationInOwnWorkspace() {
        ConversationEntity conversation = conversation();
        conversation.setAssignedEmployee(employee());
        givenConversation(conversation);

        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(true);

        service.unassignEmployee(conversationId);

        assertNull(conversation.getAssignedEmployee());
    }

    @Test
    void ordinaryEmployeeCannotUseAdministrativeAssignment() {
        givenConversation(conversation());

        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> service.assignEmployee(conversationId, employeeId)
        );

        verifyNoInteractions(employeeRepository);
    }

    @Test
    void assignmentRejectsEmployeeWithoutWorkspaceMembership() {
        ConversationEntity conversation = conversation();
        givenConversation(conversation);

        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(true);
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee()));
        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.assignEmployee(conversationId, employeeId)
        );

        assertNull(conversation.getAssignedEmployee());
    }

    @Test
    void superAdminCanAssignEmployeeInOrganizationWorkspace() {
        ConversationEntity conversation = conversation();
        EmployeeEntity employee = employee();
        givenConversation(conversation);

        when(currentUserService.isSuperAdmin()).thenReturn(true);
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(true);

        service.assignEmployee(conversationId, employeeId);

        assertSame(employee, conversation.getAssignedEmployee());
    }

    @Test
    void superAdminCannotAssignEmployeeFromAnotherOrganization() {
        ConversationEntity conversation = conversation();
        EmployeeEntity employee = employee();

        OrganizationEntity anotherOrganization =
                new OrganizationEntity("Another");
        anotherOrganization.setId(UUID.randomUUID());
        employee.setOrganization(anotherOrganization);

        givenConversation(conversation);
        when(currentUserService.isSuperAdmin()).thenReturn(true);
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));

        assertThrows(
                AccessDeniedException.class,
                () -> service.assignEmployee(conversationId, employeeId)
        );

        assertNull(conversation.getAssignedEmployee());
        verify(employeeWorkspaceRepository, never())
                .existsByEmployeeIdAndWorkspaceId(any(), any());
    }

    @Test
    void superAdminCannotUseSelfAssignment() {
        when(currentUserService.isSuperAdmin()).thenReturn(true);

        assertThrows(
                AccessDeniedException.class,
                () -> service.assignmentToMe(conversationId)
        );

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void superAdminCannotUseSelfUnassignment() {
        when(currentUserService.isSuperAdmin()).thenReturn(true);

        assertThrows(
                AccessDeniedException.class,
                () -> service.unassignmentFromMe(conversationId)
        );

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void ordinaryEmployeeCanTakeFreeConversation() {
        ConversationEntity conversation = conversation();
        EmployeeEntity employee = employee();
        givenConversation(conversation);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);

        service.assignmentToMe(conversationId);

        assertSame(employee, conversation.getAssignedEmployee());
        verify(currentUserService).requireWorkspaceAccess(workspaceId);
        verify(conversationGroupMembershipRepository).deleteAllByConversation_Id(conversationId);
    }

    @Test
    void ordinaryEmployeeCanReleaseOwnConversationAndRemoveGroupMemberships() {
        ConversationEntity conversation = conversation();
        EmployeeEntity employee = employee();
        conversation.setAssignedEmployee(employee);
        givenConversation(conversation);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);

        service.unassignmentFromMe(conversationId);

        assertNull(conversation.getAssignedEmployee());
        verify(currentUserService).requireWorkspaceAccess(workspaceId);
        verify(conversationGroupMembershipRepository).deleteAllByConversation_Id(conversationId);
    }

    @Test
    void ordinaryEmployeeCannotTakeOrReleaseAnotherEmployeesConversation() {
        ConversationEntity conversation = conversation();
        EmployeeEntity owner = employee();
        conversation.setAssignedEmployee(owner);
        EmployeeEntity another = employee();
        another.setId(UUID.randomUUID());
        givenConversation(conversation);
        when(currentUserService.getCurrentEmployee()).thenReturn(another);

        assertThrows(AccessDeniedException.class, () -> service.assignmentToMe(conversationId));
        assertThrows(AccessDeniedException.class, () -> service.unassignmentFromMe(conversationId));

        assertSame(owner, conversation.getAssignedEmployee());
        verifyNoInteractions(conversationGroupMembershipRepository);
    }

    @Test
    void ordinaryEmployeeCannotAdministrativelyUnassignFreeConversation() {
        givenConversation(conversation());

        assertThrows(AccessDeniedException.class, () -> service.unassignEmployee(conversationId));
        verifyNoInteractions(conversationGroupMembershipRepository);
    }
}
