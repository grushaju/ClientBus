package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KafkaSyncConversationHistoryCommandPublisherTest {

    @Test
    void shouldUseChannelAccountIdAsKafkaKey() {
        KafkaTemplate<String, Object> kafkaTemplate =
                mock(KafkaTemplate.class);

        KafkaSyncConversationHistoryCommandPublisher publisher =
                new KafkaSyncConversationHistoryCommandPublisher(kafkaTemplate);

        UUID channelAccountId = UUID.randomUUID();

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        channelAccountId,
                        "12345",
                        null,
                        50
                );

        publisher.publish(
                ChannelType.TELEGRAM,
                command
        );

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(
                any(String.class),
                keyCaptor.capture(),
                any(Object.class)
        );

        assertEquals(
                channelAccountId.toString(),
                keyCaptor.getValue()
        );
    }

    @Test
    void shouldUseSameKafkaKeyForCommandsOfSameChannelAccount() {
        KafkaTemplate<String, Object> kafkaTemplate =
                mock(KafkaTemplate.class);

        KafkaSyncConversationHistoryCommandPublisher publisher =
                new KafkaSyncConversationHistoryCommandPublisher(kafkaTemplate);

        UUID channelAccountId = UUID.randomUUID();

        publisher.publish(
                ChannelType.TELEGRAM,
                new SyncConversationHistoryCommand(
                        channelAccountId,
                        "100",
                        null,
                        50
                )
        );

        publisher.publish(
                ChannelType.TELEGRAM,
                new SyncConversationHistoryCommand(
                        channelAccountId,
                        "200",
                        null,
                        50
                )
        );

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate, times(2)).send(
                any(String.class),
                keyCaptor.capture(),
                any(Object.class)
        );

        assertEquals(2, keyCaptor.getAllValues().size());

        assertEquals(
                channelAccountId.toString(),
                keyCaptor.getAllValues().get(0)
        );

        assertEquals(
                channelAccountId.toString(),
                keyCaptor.getAllValues().get(1)
        );
    }

    @Test
    void shouldUseDifferentKafkaKeysForDifferentChannelAccounts() {
        KafkaTemplate<String, Object> kafkaTemplate =
                mock(KafkaTemplate.class);

        KafkaSyncConversationHistoryCommandPublisher publisher =
                new KafkaSyncConversationHistoryCommandPublisher(kafkaTemplate);

        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();

        publisher.publish(
                ChannelType.TELEGRAM,
                new SyncConversationHistoryCommand(
                        firstAccountId,
                        "100",
                        null,
                        50
                )
        );

        publisher.publish(
                ChannelType.TELEGRAM,
                new SyncConversationHistoryCommand(
                        secondAccountId,
                        "200",
                        null,
                        50
                )
        );

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate, times(2)).send(
                any(String.class),
                keyCaptor.capture(),
                any(Object.class)
        );

        assertEquals(
                firstAccountId.toString(),
                keyCaptor.getAllValues().get(0)
        );

        assertEquals(
                secondAccountId.toString(),
                keyCaptor.getAllValues().get(1)
        );
    }
}