package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.producer.ISyncRecentChatsCommandPublisher;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChannelSynchronizationListener
        implements IChannelListener {

    private final ISyncRecentChatsCommandPublisher publisher;
    private final ChannelAccountRepository channelAccountRepository;

    public ChannelSynchronizationListener(
            ISyncRecentChatsCommandPublisher publisher,
            ChannelAccountRepository channelAccountRepository
    ) {
        this.publisher = publisher;
        this.channelAccountRepository = channelAccountRepository;
    }

    @Override
    public void channelChanged(ChannelEvent event) {
        if (event == null
                || event.status() != ChannelConnectionStatus.CONNECTED) {
            return;
        }

        UUID channelAccountId = event.channelAccountId();

        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        ChannelAccountEntity channelAccount =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Channel account not found: "
                                        + channelAccountId
                        ));

        if (channelAccount.getChannel() == null) {
            throw new IllegalStateException(
                    "Channel account has no channel: "
                            + channelAccountId
            );
        }

        publisher.publish(
                channelAccount.getChannel().getType(),
                new SyncRecentChatsCommand(channelAccountId)
        );
    }
}