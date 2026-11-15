package kit.penny.clientbus.server.kafka.config;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class KafkaTopicConfiguration {

    private static final int PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;

    private final List<ChannelType> enabledOutboundChannels;

    public KafkaTopicConfiguration(
            @Value("${clientbus.kafka.outbound.enabled-channels:}")
            List<ChannelType> enabledOutboundChannels
    ) {
        this.enabledOutboundChannels =
                enabledOutboundChannels;
    }

    @Bean
    public NewTopic platformMessagesTopic() {
        return new NewTopic(
                KafkaTopicNames.platformMessages(),
                PARTITIONS,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic platformMessagesDlqTopic() {
        return new NewTopic(
                KafkaTopicNames.dlq(
                        KafkaTopicNames.platformMessages()
                ),
                1,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic platformConversationsTopic() {
        return new NewTopic(
                KafkaTopicNames.platformConversations(),
                PARTITIONS,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic platformConversationsDlqTopic() {
        return new NewTopic(
                KafkaTopicNames.dlq(
                        KafkaTopicNames.platformConversations()
                ),
                1,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic platformEventsTopic() {
        return new NewTopic(
                KafkaTopicNames.platformEvents(),
                PARTITIONS,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic platformEventsDlqTopic() {
        return new NewTopic(
                KafkaTopicNames.dlq(
                        KafkaTopicNames.platformEvents()
                ),
                1,
                REPLICATION_FACTOR
        );
    }

    /**
     * Дефект приёмки 15.11.2026: KafkaAdmin собирает только бины типа NewTopic — бины
     * List&lt;NewTopic&gt; им не читаются, поэтому per-channel топики приложением никогда
     * не создавались (живое следствие: clientbus.outbound.dikidi появился лишь авто-созданием
     * на первой публикации, topicPattern-listener его не забрал, исходящее сообщение
     * зависло QUEUED; DLQ-топики писались в несуществующие топика авто-созданием recoverer'а).
     * KafkaAdmin.NewTopics — штатная агрегирующая поставка множества NewTopic.
     */
    @Bean
    public KafkaAdmin.NewTopics outboundTopics() {
        return aggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.outbound(channelType), PARTITIONS);
    }

    @Bean
    public KafkaAdmin.NewTopics outboundDlqTopics() {
        return dlqAggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.outbound(channelType));
    }

    @Bean
    public KafkaAdmin.NewTopics channelReadTopics() {
        return aggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.channelRead(channelType), PARTITIONS);
    }

    @Bean
    public KafkaAdmin.NewTopics channelReadDlqTopics() {
        return dlqAggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.channelRead(channelType));
    }

    @Bean
    public KafkaAdmin.NewTopics recentChatsCommandTopics() {
        return aggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.recentChatsCommand(channelType), PARTITIONS);
    }

    @Bean
    public KafkaAdmin.NewTopics recentChatsCommandDlqTopics() {
        return dlqAggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.recentChatsCommand(channelType));
    }

    @Bean
    public KafkaAdmin.NewTopics conversationHistoryCommandTopics() {
        return aggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.conversationHistoryCommand(channelType), PARTITIONS);
    }

    @Bean
    public KafkaAdmin.NewTopics conversationHistoryCommandDlqTopics() {
        return dlqAggregate(enabledOutboundChannels,
                channelType -> KafkaTopicNames.conversationHistoryCommand(channelType));
    }

    private static KafkaAdmin.NewTopics aggregate(List<ChannelType> channels,
            java.util.function.Function<ChannelType, String> name, int partitions) {
        var topics = new ArrayList<NewTopic>();
        for (ChannelType channelType : channels)
            topics.add(new NewTopic(name.apply(channelType), partitions, REPLICATION_FACTOR));
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static KafkaAdmin.NewTopics dlqAggregate(List<ChannelType> channels,
            java.util.function.Function<ChannelType, String> baseName) {
        return aggregate(channels, channelType -> KafkaTopicNames.dlq(baseName.apply(channelType)), 1);
    }

    @Bean
    public NewTopic conversationHistoryResultTopic() {
        return new NewTopic(
                KafkaTopicNames.conversationHistoryResult(),
                PARTITIONS,
                REPLICATION_FACTOR
        );
    }
}