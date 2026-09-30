package kit.penny.clientbus.server.connector.command;

public sealed interface ChannelConnectorCommand
        permits SendMessageCommand,
        MarkMessagesReadCommand,
        SyncRecentChatsCommand,
        SyncConversationHistoryCommand,
        SyncAccountCommand {
}