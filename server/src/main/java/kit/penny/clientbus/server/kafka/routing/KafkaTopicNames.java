package kit.penny.clientbus.server.kafka.routing;

import kit.penny.clientbus.common.enums.ChannelType;

public final class KafkaTopicNames {

    private static final String PREFIX = "clientbus";

    private KafkaTopicNames() {
    }

    public static String inbound() {
        return PREFIX + ".inbound";
    }

    public static String platformEvents() {
        return PREFIX + ".platform-events";
    }

    public static String outbound(ChannelType channelType) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return PREFIX
                + ".outbound."
                + channelType.name().toLowerCase();
    }

    public static String dlq(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        return topic + ".dlq";
    }
}