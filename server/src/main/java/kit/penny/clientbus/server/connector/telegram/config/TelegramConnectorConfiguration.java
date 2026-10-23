package kit.penny.clientbus.server.connector.telegram.config;

import kit.penny.clientbus.server.connector.IChannelListener;
import kit.penny.clientbus.server.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramContextFactory;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kit.penny.clientbus.server.service.ChannelStatusService;
import java.util.List;

@Configuration
public class TelegramConnectorConfiguration {

    @Bean
    public TelegramContextFactory telegramContextFactory(
            TelegramProperties properties,
            ChannelAccountRepository channelAccountRepository,
            ChannelStatusService channelStatus,
            ConversationRepository conversationRepository,
            MessageService messageService,
            MessageRepository messageRepository,
            IAttachmentStorage attachmentStorage,
            IPlatformMessagePublisher platformMessagePublisher,
            TelegramAccountProfileSynchronizer profileSynchronizer,
            List<IChannelListener> channelListeners
    ) {
        return new TelegramContextFactory(
                properties,
                channelAccountRepository,
                channelStatus,
                conversationRepository,
                messageService,
                messageRepository,
                attachmentStorage,
                platformMessagePublisher,
                profileSynchronizer,
                channelListeners
        );
    }

    @Bean
    public TelegramClientManager telegramClientManager(
            TelegramContextFactory contextFactory
    ) {
        return new TelegramClientManager(contextFactory);
    }
}