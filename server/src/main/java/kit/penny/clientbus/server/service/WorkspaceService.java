package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.mapper.WorkspaceMapper;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMapper workspaceMapper;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMapper workspaceMapper
    ) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMapper = workspaceMapper;
    }

    public WorkspaceDto createWorkspace(
            CreateWorkspaceRequest request
    ) {

        if (workspaceRepository.existsByNameIgnoreCase(request.name())) {
            throw new IllegalArgumentException(
                    "Workspace with name already exists: "
                            + request.name()
            );
        }

        WorkspaceEntity entity =
                workspaceMapper.toEntity(request);

        WorkspaceEntity saved =
                workspaceRepository.saveAndFlush(entity);

        return workspaceMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public WorkspaceDto getWorkspace(UUID id) {

        WorkspaceEntity entity =
                workspaceRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Workspace not found: " + id
                                )
                        );

        return workspaceMapper.toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> getAllWorkspaces() {

        return workspaceRepository.findAll()
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    public WorkspaceDto updateWorkspace(
            UUID id,
            UpdateWorkspaceRequest request
    ) {

        WorkspaceEntity entity =
                workspaceRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Workspace not found: " + id
                                )
                        );

        if (request.name() != null &&
                !request.name().equalsIgnoreCase(entity.getName()) &&
                workspaceRepository.existsByNameIgnoreCase(request.name())) {

            throw new IllegalArgumentException(
                    "Workspace with name already exists: "
                            + request.name()
            );
        }

        workspaceMapper.updateEntity(entity, request);

        return workspaceMapper.toDto(entity);
    }

    public void deleteWorkspace(UUID id) {

        if (!workspaceRepository.existsById(id)) {
            throw new EntityNotFoundException(
                    "Workspace not found: " + id
            );
        }

        workspaceRepository.deleteById(id);
    }
}
