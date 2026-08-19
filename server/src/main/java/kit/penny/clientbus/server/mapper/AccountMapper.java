package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.account.AccountDto;
import kit.penny.clientbus.common.dto.account.CreateAccountRequest;
import kit.penny.clientbus.common.dto.account.UpdateAccountRequest;
import kit.penny.clientbus.server.persistence.entity.AccountEntity;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper {

    public AccountDto toDto(AccountEntity entity) {

        return new AccountDto(
                entity.getId(),
                entity.getClient().getId(),
                entity.getChannelType(),
                entity.getExternalId(),
                entity.getUsername(),
                entity.getPhone(),
                entity.getDisplayName()
        );
    }

    public AccountEntity toEntity(
            CreateAccountRequest request
    ) {

        return new AccountEntity(
                null,
                request.channelType(),
                request.externalId(),
                request.username(),
                request.phone(),
                request.displayName()
        );
    }

    public void updateEntity(
            AccountEntity entity,
            UpdateAccountRequest request
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