package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeWorkspaceRepository
        extends JpaRepository<EmployeeWorkspaceEntity, UUID> {

    boolean existsByEmployeeIdAndWorkspaceId(
            UUID employeeId,
            UUID workspaceId
    );

    List<EmployeeWorkspaceEntity> findAllByEmployeeId(
            UUID employeeId
    );

    List<EmployeeWorkspaceEntity> findAllByWorkspaceId(
            UUID workspaceId
    );

    void deleteByEmployeeIdAndWorkspaceId(
            UUID employeeId,
            UUID workspaceId
    );
}