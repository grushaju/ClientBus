package kit.penny.clientbus.common.dto.crm;

import java.util.List;

public record CrmWorkIntervalPageDto(List<CrmWorkIntervalDto> items, String nextCursor, boolean hasMore,
                                     CrmSyncStateDto dataState, long revision) {}
