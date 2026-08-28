package kit.penny.clientbus.server.kafka.config;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        this.enabledOutboundChannels = enabledOutboundChannels;
    }

    @Bean
    public NewTopic inboundTopic() {
        return new NewTopic(
                KafkaTopicNames.inbound(),
                PARTITIONS,
                REPLICATION_FACTOR
        );
    }

    @Bean
    public NewTopic inboundDlqTopic() {
        return new NewTopic(
                KafkaTopicNames.dlq(
                        KafkaTopicNames.inbound()
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

    @Bean
    public List<NewTopic> outboundTopics() {
        List<NewTopic> topics = new ArrayList<>();

        for (ChannelType channelType : enabledOutboundChannels) {
            topics.add(
                    new NewTopic(
                            KafkaTopicNames.outbound(channelType),
                            PARTITIONS,
                            REPLICATION_FACTOR
                    )
            );
        }

        return topics;
    }

    @Bean
    public List<NewTopic> outboundDlqTopics() {
        List<NewTopic> topics = new ArrayList<>();

        for (ChannelType channelType : enabledOutboundChannels) {
            String outboundTopic =
                    KafkaTopicNames.outbound(channelType);

            topics.add(
                    new NewTopic(
                            KafkaTopicNames.dlq(outboundTopic),
                            1,
                            REPLICATION_FACTOR
                    )
            );
        }

        return topics;
    }
}