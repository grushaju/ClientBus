package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.common.enums.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ChannelRepository
        extends JpaRepository<ChannelEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChannelEntity c join c.account a where a.id=:account")
    Optional<ChannelEntity> findLockedByAccountId(@Param("account") UUID accountId);

    List<ChannelEntity> findAllByWorkspaceId(
            UUID workspaceId
    );

    List<ChannelEntity> findAllByWorkspaceIdAndType(
            UUID workspaceId,
            ChannelType type
    );
}