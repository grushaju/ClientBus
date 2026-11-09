package kit.penny.clientbus.common.dto.crm;
import java.util.List;
public record CrmOperationPageDto(List<CrmOperationDto> items, String nextCursor, boolean hasMore) {}
