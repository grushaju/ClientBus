package kit.penny.clientbus.common.dto.crm;

import java.time.Instant;
import java.util.*;

public record CrmFinanceDto(UUID appointmentId, Map<String,Object> snapshot, Instant observedAt,
        String completeness, CrmSyncStateDto sync) {}
