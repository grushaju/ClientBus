package kit.penny.clientbus.connector.api.host;

import java.util.UUID;

/**
 * Host port applying an external read watermark for one conversation counterpart.
 * Ordering interpretation of the watermark stays with the connector; the host keeps
 * transactional status updates and UI invalidations.
 */
public interface HostMessageReadMarks {

    void markReadUpTo(
            UUID channelAccountId,
            String clientExternalId,
            long lastReadExternalId
    );
}
