package kit.penny.clientbus.server.kafka.routing;

import kit.penny.clientbus.common.enums.ChannelType;

public final class KafkaTopicNames {

    private static final String PREFIX = "clientbus";

    private static final String OUTBOUND_PREFIX =
            PREFIX + ".outbound.";

    private static final String CHANNEL_READ_PREFIX =
            PREFIX + ".channel-read.";

    private static final String CHANNEL_COMMAND_PREFIX =
            PREFIX + ".channel-command.";

    private KafkaTopicNames() {
    }

    public static String platformMessages() {
        return PREFIX + ".platform-messages";
    }

    public static String platformConversations() {
        return PREFIX + ".platform-conversations";
    }

    public static String platformEvents() {
        return PREFIX + ".platform-events";
    }

    public static String channelRead(
            ChannelType channelType
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return CHANNEL_READ_PREFIX
                + channelType.name().toLowerCase();
    }

    public static ChannelType channelReadChannelType(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        if (!topic.startsWith(CHANNEL_READ_PREFIX)) {
            throw new IllegalArgumentException(
                    "Not a channel-read topic: " + topic
            );
        }

        String channelTypeName =
                topic.substring(
                        CHANNEL_READ_PREFIX.length()
                );

        if (channelTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Channel-read topic does not contain channel type: "
                            + topic
            );
        }

        try {
            return ChannelType.valueOf(
                    channelTypeName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel type in channel-read topic: "
                            + topic,
                    e
            );
        }
    }

    public static String channelReadPattern() {
        return "^"
                + CHANNEL_READ_PREFIX.replace(".", "\\.")
                + "[^.]+$";
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
                + "[^.]+$";
    }

    public static String channelCommand(
            ChannelType channelType
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return CHANNEL_COMMAND_PREFIX
                + channelType.name().toLowerCase();
    }

    public static ChannelType channelCommandChannelType(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        if (!topic.startsWith(CHANNEL_COMMAND_PREFIX)) {
            throw new IllegalArgumentException(
                    "Not a channel-command topic: " + topic
            );
        }

        String channelTypeName =
                topic.substring(
                        CHANNEL_COMMAND_PREFIX.length()
                );

        if (channelTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Channel-command topic does not contain channel type: "
                            + topic
            );
        }

        try {
            return ChannelType.valueOf(
                    channelTypeName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel type in channel-command topic: "
                            + topic,
                    e
            );
        }
    }

    public static String channelCommandPattern() {
        return "^"
                + CHANNEL_COMMAND_PREFIX.replace(".", "\\.")
                + "[^.]+$";
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