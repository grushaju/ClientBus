package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.*;

import java.util.List;
import java.util.Optional;

public interface IChannelConnector {

    default DiscoveredClientAccount handle(DiscoverClientAccountCommand command) {
        throw new UnsupportedOperationException("Contact discovery is not supported by this connector");
    }

    default Optional<ClientAccountAvatar> handle(ReadClientAccountAvatarCommand command) {
        return Optional.empty();
    }

    boolean supports(
            ChannelType channelType
    );

    ConnectorSendResult handle(
            SendMessageCommand command
    );

    void handle(
            MarkMessagesReadCommand command
    );

    List<PlatformConversationRequest> handle(
            SyncRecentChatsCommand command
    );

    SyncConversationHistoryResult handle(
            SyncConversationHistoryCommand command
    );


    ClientAccountProfile handle(
            SyncClientAccountCommand command
    );
}
