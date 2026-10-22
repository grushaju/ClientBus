package kit.penny.clientbus.common.dto.notification;



public record HistorySyncResultDto(
        int importedCount, boolean historyStartReached
) {
}
