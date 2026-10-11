package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceMapper {

    public WorkspaceDto toDto(
            WorkspaceEntity entity
    ) {

        if (entity == null) {
            return null;
        }

        return new WorkspaceDto(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getAvatarUrl()
        );
    }

    public WorkspaceEntity toEntity(
            CreateWorkspaceRequest request,
            OrganizationEntity organization
    ) {

        if (request == null) {
            return null;
        }

        return new WorkspaceEntity(
                organization,
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