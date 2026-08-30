package kit.penny.clientbus.server.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

public class KafkaEventJsonSerializer
        implements Serializer<KafkaEvent<?>> {

    private final ObjectMapper objectMapper;

    public KafkaEventJsonSerializer() {
        this.objectMapper =
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .build();
    }

    @Override
    public byte[] serialize(
            String topic,
            KafkaEvent<?> data
    ) {

        if (data == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(data)
                    .getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to serialize KafkaEvent for topic "
                            + topic,
                    e
            );
        }
    }

    @Override
    public void close() {
        // Nothing to close.
    }
}