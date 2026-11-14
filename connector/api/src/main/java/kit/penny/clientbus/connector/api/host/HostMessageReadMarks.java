package kit.penny.clientbus.connector.api.host;

import java.util.UUID;

/**
 * Host port applying an external read watermark for one conversation counterpart.
 * Ordering interpretation of the watermark stays with the connector; the host keeps
 * transactional status updates and UI invalidations.
 *
 * <p>13.2b: the connector passes the external dialog reference when the platform addresses
 * dialogs separately from their counterpart (e.g. Telegram chat ids). The host resolves the
 * dialog by reference first and only falls back to the counterpart when that context is
 * unambiguous; an ambiguous fallback is ignored, never guessed.</p>
 */
public interface HostMessageReadMarks {

    void markReadUpTo(
            UUID channelAccountId,
            String dialogExternalId,
            String clientExternalId,
            long lastReadExternalId
    );
}
