package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.*;

import java.util.List;

public interface IChannelConnector {

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

    void handle(
            SyncAccountCommand command
    );

    ClientAccountProfile handle(
            SyncClientAccountCommand command
    );
}