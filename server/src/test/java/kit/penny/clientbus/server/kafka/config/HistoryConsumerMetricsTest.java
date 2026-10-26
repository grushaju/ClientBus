package kit.penny.clientbus.server.kafka.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryConsumerMetricsTest {
    private static final String CLIENT=HistoryConsumerMetrics.CLIENT_ID_PREFIX+"-0";
    private static final String ID="kafkaConsumerFactory."+CLIENT;
    private static final String LAG="kafka.consumer.fetch.manager.records.lag.max";

    @Test void customFactoryRegistersTheHistoryOnlyNativeMetricsListener() {
        var configuration=new KafkaConsumerConfig();
        ReflectionTestUtils.setField(configuration,"bootstrapServers","localhost:1");
        ReflectionTestUtils.setField(configuration,"autoOffsetReset","earliest");
        var meters=new SimpleMeterRegistry();
        try {
            var factory=configuration.kafkaConsumerFactory(meters);
            assertEquals(1,factory.getListeners().size());
            assertInstanceOf(HistoryConsumerMetrics.class,factory.getListeners().getFirst());
        } finally {meters.close();}
    }

    @Test void nativeLagReadsKafkaMetricsAndRemovalUnregistersMetersBeforeAStableClientRestarts() {
        var meters=new SimpleMeterRegistry();
        try {
            var listener=new HistoryConsumerMetrics(meters);
            var value=new AtomicReference<>(7d);
            var first=consumer(value);
            listener.consumerAdded(ID,first);
            try {
                var lag=meters.get(LAG).tag("pipeline","history").tag("client.id",CLIENT).gauge();
                assertEquals(7d,lag.value());
                clearInvocations(first);value.set(3d);
                assertEquals(3d,lag.value(),"The gauge reads Kafka's native metric value");
                verifyNoInteractions(first); // No position/endOffsets/committed calls during a scrape.
            } finally {listener.consumerRemoved(ID,first);}
            assertTrue(meters.getMeters().isEmpty(),"Stopped consumers remove native meters and close their binder");

            var restarted=consumer(new AtomicReference<>(11d));
            listener.consumerAdded(ID,restarted);
            try {assertEquals(11d,meters.get(LAG).tag("pipeline","history").gauge().value());}
            finally {listener.consumerRemoved(ID,restarted);}
            assertTrue(meters.getMeters().isEmpty());
        } finally {meters.close();}
    }

    @Test void otherListenersAndUnboundedClientIdsAreNotInstrumented() {
        var meters=new SimpleMeterRegistry();
        try {
            var listener=new HistoryConsumerMetrics(meters);
            @SuppressWarnings("unchecked") Consumer<String,Object> other=mock(Consumer.class);
            for(String id:java.util.List.of("kafkaConsumerFactory.connector-command-0",
                    "kafkaConsumerFactory."+HistoryConsumerMetrics.CLIENT_ID_PREFIX+"-random-id")) {
                listener.consumerAdded(id,other);listener.consumerRemoved(id,other);
            }
            verifyNoInteractions(other);assertTrue(meters.getMeters().isEmpty());
        } finally {meters.close();}
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Consumer<String,Object> consumer(AtomicReference<Double> value) {
        Consumer<String,Object> consumer=mock(Consumer.class);
        var name=new MetricName("records-lag-max","consumer-fetch-manager-metrics","Current Kafka position lag",Map.of("client-id",CLIENT));
        Metric metric=mock(Metric.class);
        when(metric.metricName()).thenReturn(name);when(metric.metricValue()).thenAnswer(ignored->value.get());
        when(consumer.metrics()).thenReturn((Map)Map.of(name,metric));
        return consumer;
    }
}
