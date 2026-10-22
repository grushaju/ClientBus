package kit.penny.clientbus.common.dto.notification;

import java.util.List;

public record HistorySyncOperationPageDto(
        List<HistorySyncOperationDto> items, String nextCursor
) {
}
