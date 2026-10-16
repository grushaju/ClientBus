package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.persistence.entity.SavedConversationFilterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SavedConversationFilterRepository
        extends JpaRepository<SavedConversationFilterEntity, UUID> {

    List<SavedConversationFilterEntity>
    findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
            UUID workspaceId,
            SavedFilterType filterType,
            UUID ownerEmployeeId
    );

    List<SavedConversationFilterEntity>
    findAllByWorkspaceIdAndFilterTypeOrderByNameAsc(
            UUID workspaceId,
            SavedFilterType filterType
    );

    Optional<SavedConversationFilterEntity>
    findByIdAndWorkspaceIdAndFilterTypeAndOwnerEmployeeId(
            UUID id,
            UUID workspaceId,
            SavedFilterType filterType,
            UUID ownerEmployeeId
    );

    Optional<SavedConversationFilterEntity>
    findByIdAndWorkspaceIdAndFilterType(
            UUID id,
            UUID workspaceId,
            SavedFilterType filterType
    );

    boolean existsByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdAndNameIgnoreCase(
            UUID workspaceId,
            SavedFilterType filterType,
            UUID ownerEmployeeId,
            String name
    );

    boolean existsByWorkspaceIdAndFilterTypeAndNameIgnoreCase(
            UUID workspaceId,
            SavedFilterType filterType,
            String name
    );

    Optional<SavedConversationFilterEntity> findByIdAndWorkspaceId(
            UUID id,
            UUID workspaceId
    );
}