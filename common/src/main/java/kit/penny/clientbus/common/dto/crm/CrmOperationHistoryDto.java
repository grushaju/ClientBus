package kit.penny.clientbus.common.dto.crm;

/** Safe card details; raw provider snapshots and request payloads are never exposed. */
public record CrmOperationHistoryDto(CrmOperationDto operation, String authorName, String oldStart,
        String newStart, String cancelComment, String zoneId) {}
