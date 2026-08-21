package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.common.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository
        extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsername(
            String username
    );

    Optional<UserEntity> findByEmail(
            String email
    );

    boolean existsByUsername(
            String username
    );

    boolean existsByEmail(
            String email
    );

    boolean existsByRole(
            UserRole role
    );

    List<UserEntity> findAllByRole(
            UserRole role
    );
}