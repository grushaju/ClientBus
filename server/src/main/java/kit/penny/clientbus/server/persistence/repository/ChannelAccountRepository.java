package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChannelAccountRepository
        extends JpaRepository<ChannelAccountEntity, UUID> {

    Optional<ChannelAccountEntity> findByChannelId(
            UUID channelId
    );
}