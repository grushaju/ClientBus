package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.common.enums.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChannelRepository
        extends JpaRepository<ChannelEntity, UUID> {

    List<ChannelEntity> findAllByWorkspaceId(
            UUID workspaceId
    );

    List<ChannelEntity> findAllByWorkspaceIdAndType(
            UUID workspaceId,
            ChannelType type
    );
}