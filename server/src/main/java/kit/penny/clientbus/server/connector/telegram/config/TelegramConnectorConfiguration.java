package kit.penny.clientbus.server.connector.telegram.config;

import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramContextFactory;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelegramConnectorConfiguration {

    @Bean
    public TelegramContextFactory telegramContextFactory(
            TelegramProperties properties,
            ChannelAccountRepository channelAccountRepository,
            ChannelRepository channelRepository,
            ConversationRepository conversationRepository,
            MessageService messageService,
            MessageRepository messageRepository,
            IAttachmentStorage attachmentStorage,
            IPlatformMessagePublisher platformMessagePublisher
    ) {
        return new TelegramContextFactory(
                properties,
                channelAccountRepository,
                channelRepository,
                conversationRepository,
                messageService,
                messageRepository,
                attachmentStorage,
                platformMessagePublisher
        );
    }

    @Bean
    public TelegramClientManager telegramClientManager(
            TelegramContextFactory contextFactory
    ) {
        return new TelegramClientManager(contextFactory);
    }
}