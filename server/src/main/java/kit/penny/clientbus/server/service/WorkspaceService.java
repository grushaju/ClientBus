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

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            WorkspaceMapper workspaceMapper
    ) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceMapper = workspaceMapper;
    }

    public WorkspaceDto createWorkspace(
            CreateWorkspaceRequest request
    ) {

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

    @Transactional(readOnly = true)
    public WorkspaceDto getWorkspace(UUID id) {

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

        return workspaceMapper.toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> getAllWorkspaces() {

        return workspaceRepository.findAll()
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> getWorkspacesByOrganization(
            UUID organizationId
    ) {

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

    public WorkspaceDto updateWorkspace(
            UUID id,
            UpdateWorkspaceRequest request
    ) {

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

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

    public void deleteWorkspace(UUID id) {

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

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