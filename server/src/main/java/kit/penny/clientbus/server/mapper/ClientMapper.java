package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ClientMapper {

    public ClientDto toDto(ClientEntity entity) {
        if (entity == null) {
            return null;
        }

        return new ClientDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getFirstName(),
                entity.getLastName(),
                List.copyOf(entity.getPhoneList()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public ClientEntity toEntity(
            CreateClientRequest request,
            WorkspaceEntity workspace
    ) {
        ClientEntity entity = new ClientEntity(
                request.firstName(),
                request.lastName(),
                workspace
        );

        entity.setPhoneList(
                request.phoneList() != null
                        ? new ArrayList<>(request.phoneList())
                        : new ArrayList<>()
        );

        return entity;
    }

    public void updateEntity(
            ClientEntity entity,
            UpdateClientRequest request
    ) {
        if (request.firstName() != null) {
            entity.setFirstName(request.firstName());
        }

        if (request.lastName() != null) {
            entity.setLastName(request.lastName());
        }

        if (request.phoneList() != null) {
            entity.setPhoneList(
                    new ArrayList<>(request.phoneList())
            );
        }

        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
    }
}