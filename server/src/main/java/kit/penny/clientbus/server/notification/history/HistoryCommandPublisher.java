package kit.penny.clientbus.server.notification.history;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Component
public class HistoryCommandPublisher {
    private final KafkaProducer<String, String> producer;
    public HistoryCommandPublisher(KafkaProperties kafka) {
        var properties = new HashMap<>(kafka.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        producer = new KafkaProducer<>(properties);
    }
    public void publish(String key, String payload) throws Exception {
        producer.send(new ProducerRecord<>(HistorySyncConfiguration.TOPIC, key, payload)).get(10, TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { producer.close(Duration.ofSeconds(2)); }
}
