package kit.penny.clientbus.common.dto.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record HistorySyncRequest(
        @Size(max = 256) String beforeExternalId, @Min(1) @Max(100) int limit
) {
}
