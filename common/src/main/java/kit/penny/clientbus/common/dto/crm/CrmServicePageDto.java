package kit.penny.clientbus.common.dto.crm;
import java.util.List;

public record CrmServicePageDto(List<CrmServiceDto> items, String nextCursor, boolean hasMore, CrmSyncStateDto dataState, long revision) {
    public CrmServicePageDto(List<CrmServiceDto> items, String nextCursor, boolean hasMore, CrmSyncStateDto dataState) { this(items, nextCursor, hasMore, dataState, 0); }
}
