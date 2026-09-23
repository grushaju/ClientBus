package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}