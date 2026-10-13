package kit.penny.clientbus.server.persistence.repository;

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
    findAllByWorkspaceIdAndIsPrivateTrueAndOwnerEmployeeIdOrderByNameAsc(
            UUID workspaceId,
            UUID ownerEmployeeId
    );

    List<SavedConversationFilterEntity>
    findAllByWorkspaceIdAndPrivateFalseOrderByNameAsc(
            UUID workspaceId
    );

    Optional<SavedConversationFilterEntity>
    findByIdAndWorkspaceIdAndIsPrivateTrueAndOwnerEmployeeId(
            UUID id,
            UUID workspaceId,
            UUID ownerEmployeeId
    );

    Optional<SavedConversationFilterEntity>
    findByIdAndWorkspaceId(
            UUID id,
            UUID workspaceId
    );

    boolean existsByWorkspaceIdAndIsPrivateTrueAndOwnerEmployeeIdAndNameIgnoreCase(
            UUID workspaceId,
            UUID ownerEmployeeId,
            String name
    );

    boolean existsByWorkspaceIdAndIsPrivateFalseAndNameIgnoreCase(
            UUID workspaceId,
            String name
    );
}