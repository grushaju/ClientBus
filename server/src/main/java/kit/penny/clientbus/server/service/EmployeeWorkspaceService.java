package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.common.dto.employee.EmployeeWorkspaceDto;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EmployeeWorkspaceService {

    private final EmployeeRepository employeeRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EmployeeWorkspaceRepository employeeWorkspaceRepository;
    private final EmployeeMapper employeeMapper;
    private final CurrentUserService currentUserService;

    public EmployeeWorkspaceService(
            EmployeeRepository employeeRepository,
            WorkspaceRepository workspaceRepository,
            EmployeeWorkspaceRepository employeeWorkspaceRepository,
            EmployeeMapper employeeMapper,
            CurrentUserService currentUserService
    ) {
        this.employeeRepository = employeeRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeWorkspaceRepository =
                employeeWorkspaceRepository;
        this.employeeMapper = employeeMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public EmployeeWorkspaceDto assignWorkspace(
            UUID employeeId,
            UUID workspaceId
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployee(employeeId);

        WorkspaceEntity workspace =
                getWorkspace(workspaceId);

        requireSameCurrentOrganization(
                employee,
                workspace
        );

        if (employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeId,
                        workspaceId
                )) {

            throw new IllegalArgumentException(
                    "Employee already has access to workspace"
            );
        }

        EmployeeWorkspaceEntity entity =
                new EmployeeWorkspaceEntity(
                        employee,
                        workspace
                );

        EmployeeWorkspaceEntity saved =
                employeeWorkspaceRepository.saveAndFlush(
                        entity
                );

        return new EmployeeWorkspaceDto(
                saved.getEmployee().getId(),
                saved.getWorkspace().getId()
        );
    }

    /**
     * SELF:
     * список собственных Workspace.
     */
    @Transactional(readOnly = true)
    public List<EmployeeWorkspaceDto>
    getCurrentEmployeeWorkspaces() {

        return getEmployeeWorkspacesInternal(
                currentUserService.getCurrentEmployeeId()
        );
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<EmployeeWorkspaceDto>
    getEmployeeWorkspaces(
            UUID employeeId
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployee(employeeId);

        if (!employee.getOrganization()
                .getId()
                .equals(
                        currentUserService
                                .getCurrentOrganizationId()
                )) {

            throw new AccessDeniedException(
                    "Employee belongs to another organization"
            );
        }

        return getEmployeeWorkspacesInternal(
                employee.getId()
        );
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getWorkspaceEmployees(
            UUID workspaceId
    ) {

        currentUserService.requireSuperAdmin();

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return employeeRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public void removeWorkspace(
            UUID employeeId,
            UUID workspaceId
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployee(employeeId);

        WorkspaceEntity workspace =
                getWorkspace(workspaceId);

        requireSameCurrentOrganization(
                employee,
                workspace
        );

        if (!employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeId,
                        workspaceId
                )) {

            throw new EntityNotFoundException(
                    "Employee workspace access not found"
            );
        }

        employeeWorkspaceRepository
                .deleteByEmployeeIdAndWorkspaceId(
                        employeeId,
                        workspaceId
                );
    }

    private List<EmployeeWorkspaceDto>
    getEmployeeWorkspacesInternal(
            UUID employeeId
    ) {

        return employeeWorkspaceRepository
                .findAllByEmployeeId(employeeId)
                .stream()
                .map(entity ->
                        new EmployeeWorkspaceDto(
                                entity.getEmployee().getId(),
                                entity.getWorkspace().getId()
                        )
                )
                .toList();
    }

    private EmployeeEntity getEmployee(
            UUID employeeId
    ) {

        return employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Employee not found: "
                                        + employeeId
                        )
                );
    }

    private WorkspaceEntity getWorkspace(
            UUID workspaceId
    ) {

        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Workspace not found: "
                                        + workspaceId
                        )
                );
    }

    private void requireSameCurrentOrganization(
            EmployeeEntity employee,
            WorkspaceEntity workspace
    ) {

        UUID currentOrganizationId =
                currentUserService
                        .getCurrentOrganizationId();

        if (!currentOrganizationId.equals(
                employee.getOrganization().getId()
        )) {

            throw new AccessDeniedException(
                    "Employee belongs to another organization"
            );
        }

        if (!currentOrganizationId.equals(
                workspace.getOrganization().getId()
        )) {

            throw new AccessDeniedException(
                    "Workspace belongs to another organization"
            );
        }
    }
}