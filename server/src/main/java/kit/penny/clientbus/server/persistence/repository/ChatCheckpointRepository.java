package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ChatCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatCheckpointRepository extends JpaRepository<ChatCheckpointEntity, UUID> {

    ChatCheckpointEntity findByChannelAccountIdAndDialogExternalId(UUID channelAccountId, String dialogExternalId);

    long deleteByChannelAccountId(UUID channelAccountId);
}
