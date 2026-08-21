package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.organization.CreateOrganizationRequest;
import kit.penny.clientbus.common.dto.organization.OrganizationDto;
import kit.penny.clientbus.common.dto.organization.UpdateOrganizationRequest;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import org.springframework.stereotype.Component;

@Component
public class OrganizationMapper {

    public OrganizationDto toDto(
            OrganizationEntity entity
    ) {
        if (entity == null) {
            return null;
        }

        return new OrganizationDto(
                entity.getId(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public OrganizationEntity toEntity(
            CreateOrganizationRequest request
    ) {
        if (request == null) {
            return null;
        }

        return new OrganizationEntity(
                request.name()
        );
    }

    public void updateEntity(
            OrganizationEntity entity,
            UpdateOrganizationRequest request
    ) {
        if (entity == null || request == null) {
            return;
        }

        if (request.name() != null) {
            entity.setName(request.name());
        }
    }
}