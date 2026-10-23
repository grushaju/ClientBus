package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import java.util.UUID;

/** Local completion signal; connector resources are released only after the result commits. */
public record OutboundSendCompleted(ChannelType channelType, UUID channelAccountId,
                                    UUID messageId, UUID sendAttemptId) {}
