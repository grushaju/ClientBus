package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.channel.ChannelAccountDto;
import kit.penny.clientbus.common.dto.channel.ChannelDto;
import kit.penny.clientbus.common.dto.channel.UpdateChannelAccountRequest;
import kit.penny.clientbus.common.dto.channel.UpdateChannelRequest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import org.springframework.stereotype.Component;

@Component
public class ChannelMapper {

    public ChannelDto toDto(ChannelEntity entity) {

        ChannelAccountDto accountDto = null;

        if (entity.getAccount() != null) {
            accountDto = toAccountDto(
                    entity.getAccount()
            );
        }

        return new ChannelDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getType(),
                entity.getName(),
                accountDto
        );
    }

    public ChannelAccountDto toAccountDto(
            ChannelAccountEntity entity
    ) {

        return new ChannelAccountDto(
                entity.getId(),
                entity.getExternalId(),
                entity.getUsername(),
                entity.getPhone(),
                entity.getDisplayName()
        );
    }

    public void updateEntity(
            ChannelEntity entity,
            UpdateChannelRequest request
    ) {

        entity.setName(request.name());
    }

    public void updateAccountEntity(
            ChannelAccountEntity entity,
            UpdateChannelAccountRequest request
    ) {

        if (request.username() != null) {
            entity.setUsername(
                    request.username()
            );
        }

        if (request.phone() != null) {
            entity.setPhone(
                    request.phone()
            );
        }

        if (request.displayName() != null) {
            entity.setDisplayName(
                    request.displayName()
            );
        }
    }
}