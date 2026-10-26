package kit.penny.clientbus.server.notification.transport;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiTransportMetricsTest {
    final NotificationProperties limits=new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("clientbus.notifications",NotificationProperties.class);
    final UiGatewayProperties config=new UiGatewayProperties("metrics-test",8,4,2,8,
            Duration.ofSeconds(1),Duration.ofMillis(50),Duration.ofMillis(50),Duration.ofSeconds(10));
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    final AtomicLong nanos=new AtomicLong(1);
    final UiTransportMetrics metrics=new UiTransportMetrics(registry,config,limits,nanos::get);
    double value(String name) {return registry.get("clientbus.notification.kafka."+name).gauge().value();}

    @Test void absentFailedAndStaleSamplesDoNotPretendToBeZeroLag() {
        assertEquals(0,value("sample.valid"));assertTrue(Double.isNaN(value("lag.records")));
        assertTrue(Double.isNaN(value("sample.age.seconds")));
        metrics.kafkaLag(12,3);assertEquals(1,value("sample.valid"));assertEquals(12,value("lag.records"));
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());metrics.kafkaUnavailable();
        assertEquals(0,value("sample.valid"));assertEquals(12,value("lag.records"));assertEquals(5,value("sample.age.seconds"));
        metrics.kafkaLag(7,3);assertEquals(1,value("sample.valid"));
        nanos.addAndGet(config.pipelineStaleAfter().toNanos());
        assertEquals(0,value("sample.valid"));assertEquals(7,value("lag.records"));
    }

    @Test void lagUsesCurrentPositionAndScrapingNeverTouchesTheConsumer() {
        @SuppressWarnings("unchecked") Consumer<String,String> consumer=mock(Consumer.class);
        var first=new TopicPartition(UiKafkaSubscriber.TOPIC,0);var second=new TopicPartition(UiKafkaSubscriber.TOPIC,1);
        when(consumer.position(first,Duration.ZERO)).thenReturn(42L);
        when(consumer.position(second,Duration.ZERO)).thenReturn(15L);
        var subscriber=new UiKafkaSubscriber(mock(UiSseGateway.class),config,limits,JsonMapper.builder().build(),new KafkaProperties(),metrics);
        subscriber.sampleLag(consumer,Map.of(first,50L,second,18L));
        verify(consumer).position(first,Duration.ZERO);verify(consumer).position(second,Duration.ZERO);
        clearInvocations(consumer);
        for(int i=0;i<3;i++){assertEquals(11,value("lag.records"));assertEquals(2,value("assigned.partitions"));assertEquals(1,value("sample.valid"));}
        verifyNoInteractions(consumer);
        assertTrue(registry.getMeters().stream().allMatch(m->m.getId().getTags().isEmpty()),"No partition, consumer, employee or event IDs are tags");
    }

    @Test void unavailablePositionInvalidatesSnapshotWithoutBreakingDeliveryReadiness() {
        @SuppressWarnings("unchecked") Consumer<String,String> consumer=mock(Consumer.class);
        var partition=new TopicPartition(UiKafkaSubscriber.TOPIC,0);
        when(consumer.position(partition,Duration.ZERO)).thenThrow(new org.apache.kafka.common.errors.TimeoutException());
        var gateway=mock(UiSseGateway.class);
        var subscriber=new UiKafkaSubscriber(gateway,config,limits,JsonMapper.builder().build(),new KafkaProperties(),metrics);
        metrics.kafkaLag(9,1);subscriber.sampleLag(consumer,Map.of(partition,50L));
        assertEquals(0,value("sample.valid"));assertEquals(9,value("lag.records"));verifyNoInteractions(gateway);
    }
}
