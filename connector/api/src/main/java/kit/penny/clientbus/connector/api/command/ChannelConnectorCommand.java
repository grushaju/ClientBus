package kit.penny.clientbus.connector.api.command;

public sealed interface ChannelConnectorCommand
        permits SendMessageCommand,
        MarkMessagesReadCommand,
        SyncRecentChatsCommand,
        SyncConversationHistoryCommand,
        DiscoverClientAccountCommand,
        ReadClientAccountAvatarCommand {
}
