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

    public EmployeeWorkspaceService(
            EmployeeRepository employeeRepository,
            WorkspaceRepository workspaceRepository,
            EmployeeWorkspaceRepository employeeWorkspaceRepository,
            EmployeeMapper employeeMapper
    ) {
        this.employeeRepository = employeeRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeWorkspaceRepository =
                employeeWorkspaceRepository;
        this.employeeMapper = employeeMapper;
    }

    public EmployeeWorkspaceDto assignWorkspace(
            UUID employeeId,
            UUID workspaceId
    ) {

        EmployeeEntity employee =
                getEmployee(employeeId);

        WorkspaceEntity workspace =
                getWorkspace(workspaceId);

        validateSameOrganization(
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

    @Transactional(readOnly = true)
    public List<EmployeeWorkspaceDto> getEmployeeWorkspaces(
            UUID employeeId
    ) {

        getEmployee(employeeId);

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

    @Transactional(readOnly = true)
    public List<EmployeeDto> getWorkspaceEmployees(
            UUID workspaceId
    ) {

        getWorkspace(workspaceId);

        return employeeRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    public void removeWorkspace(
            UUID employeeId,
            UUID workspaceId
    ) {

        getEmployee(employeeId);

        getWorkspace(workspaceId);

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

    private void validateSameOrganization(
            EmployeeEntity employee,
            WorkspaceEntity workspace
    ) {

        if (!employee.getOrganization()
                .getId()
                .equals(
                        workspace.getOrganization()
                                .getId()
                )) {

            throw new IllegalArgumentException(
                    "Employee and Workspace belong to different organizations"
            );
        }
    }
}