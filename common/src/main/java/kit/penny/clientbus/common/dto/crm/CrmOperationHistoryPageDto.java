package kit.penny.clientbus.common.dto.crm;

import java.util.List;
public record CrmOperationHistoryPageDto(List<CrmOperationHistoryDto> items, String nextCursor, boolean hasMore) {}
