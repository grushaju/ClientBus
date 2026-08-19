package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository
        extends JpaRepository<EmployeeEntity, UUID> {

    List<EmployeeEntity> findAllByWorkspaceId(
            UUID workspaceId
    );

    List<EmployeeEntity> findAllByWorkspaceIdAndIsEnabledTrue(
            UUID workspaceId
    );

    List<EmployeeEntity> findAllByWorkspaceIdAndIsEnabledFalse(
            UUID workspaceId
    );

    Optional<EmployeeEntity> findByUserId(
            UUID userId
    );

    Optional<EmployeeEntity> findByUserLogin(
            String login
    );

    boolean existsByUserId(
            UUID userId
    );

    boolean existsByWorkspaceIdAndEmail(
            UUID workspaceId,
            String email
    );

    boolean existsByWorkspaceIdAndEmailAndIdNot(
            UUID workspaceId,
            String email,
            UUID id
    );
}