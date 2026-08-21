package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.mapper.WorkspaceMapper;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceMapper workspaceMapper;
    private final CurrentUserService currentUserService;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            WorkspaceMapper workspaceMapper,
            CurrentUserService currentUserService
    ) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceMapper = workspaceMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public WorkspaceDto createWorkspace(
            CreateWorkspaceRequest request
    ) {

        currentUserService.requireSuperAdminOrganization(
                request.organizationId()
        );

        OrganizationEntity organization =
                organizationRepository.findById(
                        request.organizationId()
                ).orElseThrow(() ->
                        new EntityNotFoundException(
                                "Organization not found: "
                                        + request.organizationId()
                        )
                );

        if (workspaceRepository
                .existsByOrganizationIdAndNameIgnoreCase(
                        request.organizationId(),
                        request.name()
                )) {

            throw new IllegalArgumentException(
                    "Workspace with name already exists in organization: "
                            + request.name()
            );
        }

        WorkspaceEntity entity =
                workspaceMapper.toEntity(
                        request,
                        organization
                );

        WorkspaceEntity saved =
                workspaceRepository.saveAndFlush(entity);

        return workspaceMapper.toDto(saved);
    }

    /**
     * SUPER_ADMIN + EMPLOYEE.
     *
     * EMPLOYEE получает только Workspace,
     * назначенный ему.
     */
    @Transactional(readOnly = true)
    public WorkspaceDto getWorkspace(UUID id) {

        currentUserService.requireWorkspaceAccess(id);

        return workspaceMapper.toDto(
                getWorkspaceEntity(id)
        );
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto> getAllWorkspaces() {

        currentUserService.requireSuperAdmin();

        return workspaceRepository.findAll()
                .stream()
                .filter(workspace ->
                        workspace.getOrganization()
                                .getId()
                                .equals(
                                        currentUserService
                                                .getCurrentOrganizationId()
                                )
                )
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto> getWorkspacesByOrganization(
            UUID organizationId
    ) {

        currentUserService.requireSuperAdminOrganization(
                organizationId
        );

        if (!organizationRepository.existsById(
                organizationId
        )) {

            throw new EntityNotFoundException(
                    "Organization not found: "
                            + organizationId
            );
        }

        return workspaceRepository
                .findAllByOrganizationId(organizationId)
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SELF + SUPER_ADMIN.
     *
     * Возвращает только Workspace,
     * доступные текущему пользователю.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto>
    getCurrentUserWorkspaces() {

        UUID organizationId =
                currentUserService
                        .getCurrentOrganizationId();

        if (currentUserService.isSuperAdmin()) {

            return workspaceRepository
                    .findAllByOrganizationId(organizationId)
                    .stream()
                    .map(workspaceMapper::toDto)
                    .toList();
        }

        UUID employeeId =
                currentUserService
                        .getCurrentEmployeeId();

        return workspaceRepository
                .findAllWorkspacesByEmployeeId(employeeId)
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public WorkspaceDto updateWorkspace(
            UUID id,
            UpdateWorkspaceRequest request
    ) {

        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

        currentUserService.requireWorkspaceAccess(id);

        if (request.name() != null &&
                !request.name().equalsIgnoreCase(
                        entity.getName()
                ) &&
                workspaceRepository
                        .existsByOrganizationIdAndNameIgnoreCase(
                                entity.getOrganization().getId(),
                                request.name()
                        )) {

            throw new IllegalArgumentException(
                    "Workspace with name already exists in organization: "
                            + request.name()
            );
        }

        workspaceMapper.updateEntity(
                entity,
                request
        );

        return workspaceMapper.toDto(entity);
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public void deleteWorkspace(UUID id) {

        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

        currentUserService.requireWorkspaceAccess(id);

        workspaceRepository.delete(entity);
    }

    private WorkspaceEntity getWorkspaceEntity(
            UUID id
    ) {

        return workspaceRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Workspace not found: " + id
                        )
                );
    }
}