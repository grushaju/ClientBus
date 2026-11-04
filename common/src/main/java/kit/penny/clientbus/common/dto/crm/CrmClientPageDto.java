package kit.penny.clientbus.common.dto.crm;
import java.util.List;
public record CrmClientPageDto(List<CrmClientDto> items, String nextCursor, boolean hasMore, CrmSyncStateDto syncState, long revision) {}
