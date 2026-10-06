package kit.penny.clientbus.server.persistence.repository;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelAccountRepository
        extends JpaRepository<ChannelAccountEntity, UUID> {

    Optional<ChannelAccountEntity> findByChannelId(
            UUID channelId
    );

    List<ChannelAccountEntity> findAllByChannelTypeAndChannelStatus(
            ChannelType type,
            ChannelConnectionStatus status
    );

    List<ChannelAccountEntity>
    findAllByChannelTypeAndChannelStatusIn(
            ChannelType channelType,
            Collection<ChannelConnectionStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT account
        FROM ChannelAccountEntity account
        WHERE account.id = :channelAccountId
        """)
    Optional<ChannelAccountEntity> findByIdForUpdate(
            @Param("channelAccountId")
            UUID channelAccountId
    );
}