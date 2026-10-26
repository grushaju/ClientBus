package kit.penny.clientbus.server.kafka.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.kafka.core.MicrometerConsumerListener;

import java.util.List;
import java.util.regex.Pattern;

/** Native Kafka metrics for the one History listener; no extra consumer or committed-offset polling. */
public final class HistoryConsumerMetrics extends MicrometerConsumerListener<String,Object> {
    public static final String CLIENT_ID_PREFIX="clientbus-history-operation";
    private static final Pattern CLIENT_ID=Pattern.compile(Pattern.quote(CLIENT_ID_PREFIX)+"(?:-[0-9]+)?");

    public HistoryConsumerMetrics(MeterRegistry registry) {
        super(registry,List.of(Tag.of("pipeline","history")));
    }

    @Override public synchronized void consumerAdded(String id,Consumer<String,Object> consumer) {
        // Factory callback IDs are <factory bean name>.<effective client.id>.
        // The fixed listener prefix plus optional concurrency index keeps native client tags stable.
        String clientId=id.substring(id.lastIndexOf('.')+1);
        if(CLIENT_ID.matcher(clientId).matches()) super.consumerAdded(id,consumer);
    }
    // Inherited consumerRemoved closes the KafkaClientMetrics binder, its refresh thread and its meters.
}
