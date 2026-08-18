package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
    Optional<WorkspaceEntity> findByName(String name);
    boolean existsByName(String name);
}