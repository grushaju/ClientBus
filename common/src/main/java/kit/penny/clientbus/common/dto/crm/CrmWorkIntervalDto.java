package kit.penny.clientbus.common.dto.crm;

import java.time.*;
import java.util.*;

public record CrmWorkIntervalDto(UUID id, UUID projectId, UUID masterId, LocalDateTime localStart, LocalDateTime localEnd,
                                 String kind, String externalRef, Map<String, Object> attributes, Instant lastObservedAt, long version) {}
