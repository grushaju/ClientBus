package kit.penny.clientbus.server.notification.contract;

import kit.penny.clientbus.common.dto.notification.HistorySyncResultDto;
import java.util.UUID;

/** A completion belongs to one acquired worker claim, never just to a conversation. */
public record HistorySyncCompletion(
        UUID operationId, long claimVersion, HistorySyncResultDto result, String errorCode
) {
    public HistorySyncCompletion {
        if (operationId == null || claimVersion <= 0 || (result == null) == (errorCode == null)) {
            throw new IllegalArgumentException("Invalid history completion");
        }
    }
}
