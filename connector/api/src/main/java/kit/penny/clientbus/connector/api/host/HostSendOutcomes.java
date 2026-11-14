package kit.penny.clientbus.connector.api.host;

import java.util.UUID;

/**
 * Host port recording the terminal outcome of an outbound attempt observed as a platform update:
 * the temporary external id replaced by the final one, or the failure. The host decides how the
 * send attempt is persisted and guarded against late results of earlier attempts.
 */
public interface HostSendOutcomes {

    void recordExternalOutcome(
            UUID channelAccountId,
            String chatExternalId,
            String oldExternalId,
            String newExternalId,
            boolean failed
    );
}
