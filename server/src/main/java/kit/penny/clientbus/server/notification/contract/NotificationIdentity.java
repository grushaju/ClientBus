package kit.penny.clientbus.server.notification.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Length-prefixed UTF-8 fields avoid separator collisions. Identity is scoped by organization. */
public final class NotificationIdentity {
    private NotificationIdentity() {}

    public static String incoming(UUID channelAccountId, String externalMessageId) {
        return digest("N2/v1", channelAccountId.toString(), externalMessageId);
    }

    public static String outboundFailure(OutboundAttemptContext attempt) {
        return digest("N3/v1", attempt.messageId().toString(), attempt.sendAttemptId().toString());
    }

    public static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
