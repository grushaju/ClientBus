package kit.penny.clientbus.common.dto.crm;

import java.util.List;

public record CrmClientSearchDto(List<CrmClientDto> items, boolean hasMore) {}
