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

    private static final String RECENT_CHATS_COMMAND_PREFIX =
            CHANNEL_COMMAND_PREFIX + "recent-chats.";

    private static final String CONVERSATION_HISTORY_COMMAND_PREFIX =
            CHANNEL_COMMAND_PREFIX + "conversation-history.";

    private static final String CONVERSATION_HISTORY_RESULT =
            PREFIX + ".conversation-history-result";

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

    public static String recentChatsCommand(
            ChannelType channelType
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return RECENT_CHATS_COMMAND_PREFIX
                + channelType.name().toLowerCase();
    }

    public static ChannelType recentChatsCommandChannelType(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        if (!topic.startsWith(
                RECENT_CHATS_COMMAND_PREFIX
        )) {
            throw new IllegalArgumentException(
                    "Not a recent-chats command topic: "
                            + topic
            );
        }

        String channelTypeName =
                topic.substring(
                        RECENT_CHATS_COMMAND_PREFIX.length()
                );

        if (channelTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Recent-chats command topic does not contain channel type: "
                            + topic
            );
        }

        try {
            return ChannelType.valueOf(
                    channelTypeName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel type in recent-chats command topic: "
                            + topic,
                    e
            );
        }
    }

    public static String recentChatsCommandPattern() {
        return "^"
                + RECENT_CHATS_COMMAND_PREFIX
                .replace(".", "\\.")
                + "[^.]+$";
    }

    public static String conversationHistoryCommand(
            ChannelType channelType
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        return CONVERSATION_HISTORY_COMMAND_PREFIX
                + channelType.name().toLowerCase();
    }

    public static ChannelType conversationHistoryCommandChannelType(
            String topic
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Topic must not be blank"
            );
        }

        if (!topic.startsWith(
                CONVERSATION_HISTORY_COMMAND_PREFIX
        )) {
            throw new IllegalArgumentException(
                    "Not a conversation-history command topic: "
                            + topic
            );
        }

        String channelTypeName =
                topic.substring(
                        CONVERSATION_HISTORY_COMMAND_PREFIX.length()
                );

        if (channelTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Conversation-history command topic does not contain channel type: "
                            + topic
            );
        }

        try {
            return ChannelType.valueOf(
                    channelTypeName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel type in conversation-history command topic: "
                            + topic,
                    e
            );
        }
    }

    public static String conversationHistoryCommandPattern() {
        return "^"
                + CONVERSATION_HISTORY_COMMAND_PREFIX
                .replace(".", "\\.")
                + "[^.]+$";
    }

    public static String conversationHistoryResult() {
        return CONVERSATION_HISTORY_RESULT;
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