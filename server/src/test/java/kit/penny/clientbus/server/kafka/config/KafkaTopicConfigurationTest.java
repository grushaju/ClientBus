package kit.penny.clientbus.server.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTopicConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTopicConfiguration.class);

    @Test void yamlChannelListDeclaresDikidiCommandAndHistoryTopicsBeforeFirstUse() {
        context.withPropertyValues("clientbus.kafka.outbound.enabled-channels[0]=TELEGRAM",
                "clientbus.kafka.outbound.enabled-channels[1]=DIKIDI").run(this::assertBothChannels);
    }

    @Test void commaSeparatedChannelListRemainsSupported() {
        context.withPropertyValues("clientbus.kafka.outbound.enabled-channels=TELEGRAM,DIKIDI")
                .run(this::assertBothChannels);
    }

    private void assertBothChannels(org.springframework.boot.test.context.assertj.AssertableApplicationContext application) {
        assertNull(application.getStartupFailure());
        assertEquals(Set.of("clientbus.channel-command.recent-chats.telegram",
                "clientbus.channel-command.recent-chats.dikidi"), topics(application, "recentChatsCommandTopics"));
        assertEquals(Set.of("clientbus.channel-command.conversation-history.telegram",
                "clientbus.channel-command.conversation-history.dikidi"), topics(application, "conversationHistoryCommandTopics"));
        assertEquals(Set.of("clientbus.outbound.telegram", "clientbus.outbound.dikidi"),
                topics(application, "outboundTopics"));
        assertEquals(Set.of("clientbus.channel-command.recent-chats.telegram.dlq",
                "clientbus.channel-command.recent-chats.dikidi.dlq"), topics(application, "recentChatsCommandDlqTopics"));
    }

    @SuppressWarnings("unchecked")
    private Set<String> topics(org.springframework.context.ApplicationContext application, String beanName) {
        var aggregate = application.getBean(beanName, KafkaAdmin.NewTopics.class);
        var declarations = (Collection<NewTopic>) ReflectionTestUtils.getField(aggregate, "newTopics");
        assertNotNull(declarations);
        return declarations.stream().map(NewTopic::name).collect(Collectors.toSet());
    }
}
