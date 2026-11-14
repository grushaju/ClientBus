package kit.penny.clientbus.connector.telegram.config;

import kit.penny.clientbus.connector.api.listener.IChannelListener;
import kit.penny.clientbus.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.connector.telegram.client.TelegramContextFactory;
import kit.penny.clientbus.connector.api.host.HostAttachmentStorage;
import kit.penny.clientbus.connector.api.host.HostInboundPublisher;
import kit.penny.clientbus.connector.api.host.HostMessageReadMarks;
import kit.penny.clientbus.connector.api.host.HostSendOutcomes;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import java.util.List;

@Configuration
public class TelegramConnectorConfiguration {

    @Bean
    public TelegramContextFactory telegramContextFactory(
            TelegramProperties properties,
            HostChannelAccounts hostChannelAccounts,
            HostChannelStatus channelStatus,
            HostMessageReadMarks hostMessageReadMarks,
            HostSendOutcomes hostSendOutcomes,
            HostAttachmentStorage hostAttachmentStorage,
            HostInboundPublisher hostInboundPublisher,
            TelegramAccountProfileSynchronizer profileSynchronizer,
            List<IChannelListener> channelListeners
    ) {
        return new TelegramContextFactory(
                properties,
                hostChannelAccounts,  channelStatus,
                hostMessageReadMarks,
                hostSendOutcomes,
                hostAttachmentStorage,
                hostInboundPublisher,
                profileSynchronizer,
                channelListeners
        );
    }

    @Bean(destroyMethod = "closeAll")
    public TelegramClientManager telegramClientManager(
            TelegramContextFactory contextFactory
    ) {
        return new TelegramClientManager(contextFactory);
    }
}