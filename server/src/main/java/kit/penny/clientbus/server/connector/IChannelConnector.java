package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.connector.command.SyncAccountCommand;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;

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

    void handle(
            SyncConversationHistoryCommand command
    );

    void handle(
            SyncAccountCommand command
    );
}