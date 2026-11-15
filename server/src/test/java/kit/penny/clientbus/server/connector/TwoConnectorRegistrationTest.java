package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.dikidi.DikidiChannelConnector;
import kit.penny.clientbus.connector.dikidi.DikidiChatAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.4 результат: Telegram и DIKIDI зарегистрированы одновременно; каналы
 * разведены по типу, дубликат того же типа реестр отвергает.
 */
class TwoConnectorRegistrationTest {
    private static final class TelegramStub implements IChannelConnector {
        @Override public boolean supports(ChannelType channelType) { return channelType == ChannelType.TELEGRAM; }
        @Override public java.util.List<kit.penny.clientbus.connector.api.model.ConnectorConversation>
        handle(kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand command) { return List.of(); }
        @Override public kit.penny.clientbus.connector.api.model.SyncConversationHistoryResult
        handle(kit.penny.clientbus.connector.api.command.SyncConversationHistoryCommand command) { throw new UnsupportedOperationException(); }
        @Override public kit.penny.clientbus.connector.api.model.ConnectorSendResult
        handle(kit.penny.clientbus.connector.api.command.SendMessageCommand command) { throw new UnsupportedOperationException(); }
        @Override public void handle(kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand command) { }
    }
    private static DikidiChannelConnector dikidi() {
        return new DikidiChannelConnector(null, null, null, null, null, null, null, 5);
    }
    @Test void telegramAndDikidiCoexistInOneRegistry() {
        var telegram = new TelegramStub();
        var dikidi = dikidi();
        var registry = new ChannelConnectorRegistry(List.of(telegram, dikidi));
        assertSame(telegram, registry.getConnector(ChannelType.TELEGRAM));
        assertSame(dikidi, registry.getConnector(ChannelType.DIKIDI));
    }
    @Test void duplicateDikidiChannelIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new ChannelConnectorRegistry(List.of(dikidi(), dikidi())));
    }
}
