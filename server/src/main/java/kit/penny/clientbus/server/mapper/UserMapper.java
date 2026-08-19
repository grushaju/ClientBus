package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.user.UserDto;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserDto toDto(UserEntity entity) {

        if (entity == null) {
            return null;
        }

        return new UserDto(
                entity.getId(),
                entity.getLogin(),
                entity.isEnabled()
        );
    }
}