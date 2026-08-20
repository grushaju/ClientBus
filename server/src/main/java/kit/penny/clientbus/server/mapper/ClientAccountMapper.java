package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.dto.clientaccount.UpdateClientAccountRequest;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ClientAccountMapper {

    public ClientAccountDto toDto(ClientAccountEntity entity) {
        UUID clientId = null;
        if (entity.getClient() != null) {
            clientId = entity.getClient().getId();
        }
        return new ClientAccountDto(
                entity.getId(),
                clientId,
                entity.getChannelType(),
                entity.getExternalId(),
                entity.getUsername(),
                entity.getPhone(),
                entity.getDisplayName()
        );
    }

    public ClientAccountEntity toEntity(
            CreateClientAccountRequest request
    ) {

        return new ClientAccountEntity(
                null,
                request.channelType(),
                request.externalId(),
                request.username(),
                request.phone(),
                request.displayName()
        );
    }

    public void updateEntity(
            ClientAccountEntity entity,
            UpdateClientAccountRequest request
    ) {

        if (request.username() != null) {
            entity.setUsername(request.username());
        }

        if (request.phone() != null) {
            entity.setPhone(request.phone());
        }

        if (request.displayName() != null) {
            entity.setDisplayName(request.displayName());
        }
    }
}