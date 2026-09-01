package kit.penny.clientbus.server.kafka.routing;

import kit.penny.clientbus.common.enums.ChannelType;

public final class KafkaTopicNames {

    private static final String PREFIX = "clientbus";

    private static final String OUTBOUND_PREFIX =
            PREFIX + ".outbound.";

    private KafkaTopicNames() {
    }

    public static String inbound() {
        return PREFIX + ".inbound";
    }

    public static String platformEvents() {
        return PREFIX + ".platform-events";
    }

    public static String outbound(
            ChannelType channelType
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return OUTBOUND_PREFIX
                + channelType.name().toLowerCase();
    }

    public static ChannelType outboundChannelType(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        if (!topic.startsWith(OUTBOUND_PREFIX)) {
            throw new IllegalArgumentException(
                    "Not an outbound topic: " + topic
            );
        }

        String channelTypeName =
                topic.substring(
                        OUTBOUND_PREFIX.length()
                );

        if (channelTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Outbound topic does not contain channel type: "
                            + topic
            );
        }

        try {
            return ChannelType.valueOf(
                    channelTypeName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel type in outbound topic: "
                            + topic,
                    e
            );
        }
    }

    public static String outboundPattern() {
        return "^"
                + OUTBOUND_PREFIX.replace(".", "\\.")
                + ".*$";
    }

    public static String dlq(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        return topic + ".dlq";
    }
}