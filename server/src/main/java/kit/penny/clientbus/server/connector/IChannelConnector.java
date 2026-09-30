package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.connector.command.SyncAccountCommand;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;

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

    void handle(
            SyncRecentChatsCommand command
    );

    void handle(
            SyncConversationHistoryCommand command
    );

    void handle(
            SyncAccountCommand command
    );
}