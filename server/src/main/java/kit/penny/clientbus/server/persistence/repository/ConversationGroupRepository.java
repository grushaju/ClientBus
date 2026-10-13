package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ConversationGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationGroupRepository
        extends JpaRepository<ConversationGroupEntity, UUID> {

    List<ConversationGroupEntity>
    findAllByWorkspaceIdAndOwnerEmployeeIdOrderByNameAsc(
            UUID workspaceId,
            UUID ownerEmployeeId
    );

    Optional<ConversationGroupEntity>
    findByIdAndWorkspaceIdAndOwnerEmployeeId(
            UUID id,
            UUID workspaceId,
            UUID ownerEmployeeId
    );

    boolean existsByWorkspaceIdAndOwnerEmployeeIdAndNameIgnoreCase(
            UUID workspaceId,
            UUID ownerEmployeeId,
            String name
    );
}