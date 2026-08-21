package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository
        extends JpaRepository<WorkspaceEntity, UUID> {

    Optional<WorkspaceEntity> findByNameIgnoreCase(
            String name
    );

    boolean existsByNameIgnoreCase(
            String name
    );

    List<WorkspaceEntity> findAllByOrganizationId(
            UUID organizationId
    );

    Optional<WorkspaceEntity> findByOrganizationIdAndNameIgnoreCase(
            UUID organizationId,
            String name
    );

    @Query("""
        select ew.workspace
        from EmployeeWorkspaceEntity ew
        where ew.employee.id = :employeeId
        """)
    List<WorkspaceEntity> findAllWorkspacesByEmployeeId(
            @Param("employeeId") UUID employeeId
    );

    boolean existsByOrganizationIdAndNameIgnoreCase(
            UUID organizationId,
            String name
    );

    Optional<WorkspaceEntity> findByIdAndOrganizationId(
            UUID workspaceId,
            UUID organizationId
    );

    boolean existsByIdAndOrganizationId(
            UUID workspaceId,
            UUID organizationId
    );
}