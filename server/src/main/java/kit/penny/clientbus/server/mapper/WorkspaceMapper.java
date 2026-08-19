package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceMapper {

    public WorkspaceDto toDto(WorkspaceEntity entity) {
        if (entity == null) {
            return null;
        }

        return new WorkspaceDto(
                entity.getId(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public WorkspaceEntity toEntity(CreateWorkspaceRequest request) {
        if (request == null) {
            return null;
        }

        return new WorkspaceEntity(
                request.name()
        );
    }

    public void updateEntity(
            WorkspaceEntity entity,
            UpdateWorkspaceRequest request
    ) {
        if (entity == null || request == null) {
            return;
        }

        if (request.name() != null) {
            entity.setName(request.name());
        }
    }
}
