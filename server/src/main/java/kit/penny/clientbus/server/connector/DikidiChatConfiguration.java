package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.connector.api.host.HostChannelAccess;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.connector.api.host.HostConversationCheckpoints;
import kit.penny.clientbus.connector.api.host.HostInboundPublisher;
import kit.penny.clientbus.connector.dikidi.DikidiChannelConnector;
import kit.penny.clientbus.connector.dikidi.DikidiChannelLifecycle;
import kit.penny.clientbus.connector.dikidi.DikidiChatAccess;
import kit.penny.clientbus.connector.dikidi.DikidiChatChannel;
import kit.penny.clientbus.connector.dikidi.DikidiChatManagementController;
import kit.penny.clientbus.connector.dikidi.DikidiChatReads;
import kit.penny.clientbus.connector.dikidi.DikidiTechnicalCredentials;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Сборка чатового канала DIKIDI (13.5): модуль connector/dikidi остаётся библиотекой,
 * сервер собирает его бины на общем DikidiClient (одна HTTP-сессия, один token-бакет,
 * один lease-guard на подключение — CRM и чат входят в DIKIDI один раз на версию
 * credentials). Конфигурационный binding (connection-id/company) удалён: привязка
 * берётся из аккаунта канала, провизионенного назначением DIKIDI-проекта.
 */
@Configuration
public class DikidiChatConfiguration {
    @Bean
    public DikidiChatReads dikidiChatReads() {
        return new DikidiChatReads();
    }
    @Bean
    public DikidiChatAccess dikidiChatAccess(DikidiClient client, DikidiTechnicalCredentials credentials,
            HostChannelAccounts accounts, DikidiChatReads reads) {
        return new DikidiChatAccess(client, credentials, accounts, reads);
    }
    @Bean
    public DikidiChannelConnector dikidiChannelConnector(DikidiClient client, DikidiTechnicalCredentials credentials,
            HostChannelAccounts accounts, HostConversationCheckpoints checkpoints, HostInboundPublisher inbound,
            DikidiChatReads reads, DikidiChatAccess chatAccess,
            kit.penny.clientbus.connector.api.host.HostAttachmentStorage attachmentStorage,
            @Value("${clientbus.dikidi.chat.initial-pages:5}") int initialPages) {
        return new DikidiChannelConnector(client, credentials, accounts, checkpoints, inbound, reads,
                chatAccess, initialPages, attachmentStorage);
    }
    @Bean
    public DikidiChatChannel dikidiChatChannel(DikidiChannelConnector connector,
            ObjectProvider<HostChannelAccounts> accounts, ObjectProvider<HostChannelStatus> status,
            ObjectProvider<kit.penny.clientbus.connector.api.listener.IChannelListener> listeners) {
        return new DikidiChatChannel(connector, accounts, status, listeners);
    }
    @Bean
    public DikidiChannelLifecycle dikidiChannelLifecycle(HostChannelStatus status) {
        return new DikidiChannelLifecycle(status);
    }
    @Bean
    public DikidiChatManagementController dikidiChatManagementController(DikidiChatChannel channel,
            HostChannelAccess hostChannelAccess) {
        return new DikidiChatManagementController(channel, hostChannelAccess);
    }
}
