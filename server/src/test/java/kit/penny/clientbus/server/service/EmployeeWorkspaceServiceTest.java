package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.employee.EmployeeWorkspaceDto;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeWorkspaceServiceTest {

    private EmployeeRepository employeeRepository;
    private WorkspaceRepository workspaceRepository;
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;
    private EmployeeMapper employeeMapper;
    private CurrentUserService currentUserService;

    private EmployeeWorkspaceService service;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        employeeWorkspaceRepository =
                mock(EmployeeWorkspaceRepository.class);
        employeeMapper = mock(EmployeeMapper.class);
        currentUserService = mock(CurrentUserService.class);

        service = new EmployeeWorkspaceService(
                employeeRepository,
                workspaceRepository,
                employeeWorkspaceRepository,
                employeeMapper,
                currentUserService
        );
    }

    @Test
    void setSupervisorEnablesSupervisorAndPreservesWorkspaceLink() {
        UUID employeeId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        EmployeeEntity employee =
                employeeInOrganization(employeeId, organizationId);
        WorkspaceEntity workspace =
                workspaceInOrganization(workspaceId, organizationId);

        EmployeeWorkspaceEntity link =
                new EmployeeWorkspaceEntity(employee, workspace);

        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);
        when(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(Optional.of(link));
        when(employeeWorkspaceRepository.save(link))
                .thenReturn(link);

        EmployeeWorkspaceDto result =
                service.setSupervisor(employeeId, workspaceId, true);

        assertTrue(link.isSupervisor());
        assertTrue(result.isSupervisor());
        assertEquals(employeeId, result.employeeId());
        assertEquals(workspaceId, result.workspaceId());

        verify(employeeWorkspaceRepository).save(link);
        verify(employeeWorkspaceRepository, never())
                .deleteByEmployeeIdAndWorkspaceId(employeeId, workspaceId);
    }

    @Test
    void setSupervisorCanDisableSupervisor() {
        UUID employeeId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        EmployeeEntity employee =
                employeeInOrganization(employeeId, organizationId);
        WorkspaceEntity workspace =
                workspaceInOrganization(workspaceId, organizationId);
        EmployeeWorkspaceEntity link =
                new EmployeeWorkspaceEntity(employee, workspace);
        link.setSupervisor(true);

        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);
        when(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(Optional.of(link));
        when(employeeWorkspaceRepository.save(link))
                .thenReturn(link);

        EmployeeWorkspaceDto result =
                service.setSupervisor(employeeId, workspaceId, false);

        assertFalse(link.isSupervisor());
        assertFalse(result.isSupervisor());
        verify(employeeWorkspaceRepository).save(link);
    }

    @Test
    void setSupervisorFailsWhenEmployeeHasNoWorkspaceLink() {
        UUID employeeId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        EmployeeEntity employee =
                employeeInOrganization(employeeId, organizationId);
        WorkspaceEntity workspace =
                workspaceInOrganization(workspaceId, organizationId);
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);
        when(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(employeeId, workspaceId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> service.setSupervisor(employeeId, workspaceId, true)
        );

        verify(employeeWorkspaceRepository, never()).save(any());
    }

    @Test
    void setSupervisorRejectsEmployeeFromAnotherOrganization() {
        UUID employeeId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        UUID currentOrganizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();

        EmployeeEntity employee =
                employeeInOrganization(employeeId, otherOrganizationId);
        WorkspaceEntity workspace =
                workspaceInOrganization(workspaceId, otherOrganizationId);

        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(employee));
        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(currentOrganizationId);

        assertThrows(
                AccessDeniedException.class,
                () -> service.setSupervisor(employeeId, workspaceId, true)
        );

        verify(employeeWorkspaceRepository, never()).save(any());
    }

    @Test
    void setSupervisorRequiresSuperAdmin() {
        UUID employeeId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        doThrow(new AccessDeniedException("SUPER_ADMIN role required"))
                .when(currentUserService)
                .requireSuperAdmin();

        assertThrows(
                AccessDeniedException.class,
                () -> service.setSupervisor(employeeId, workspaceId, true)
        );

        verifyNoInteractions(employeeRepository, workspaceRepository);
        verify(employeeWorkspaceRepository, never()).save(any());
    }

    private EmployeeEntity employeeInOrganization(
            UUID employeeId,
            UUID organizationId
    ) {
        EmployeeEntity employee = mock(EmployeeEntity.class);
        OrganizationEntity organization = mock(OrganizationEntity.class);

        when(employee.getId()).thenReturn(employeeId);
        when(employee.getOrganization()).thenReturn(organization);
        when(organization.getId()).thenReturn(organizationId);

        return employee;
    }

    private WorkspaceEntity workspaceInOrganization(
            UUID workspaceId,
            UUID organizationId
    ) {
        WorkspaceEntity workspace = mock(WorkspaceEntity.class);
        OrganizationEntity organization = mock(OrganizationEntity.class);

        when(workspace.getId()).thenReturn(workspaceId);
        when(workspace.getOrganization()).thenReturn(organization);
        when(organization.getId()).thenReturn(organizationId);

        return workspace;
    }
}
