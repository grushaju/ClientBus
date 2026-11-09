package kit.penny.clientbus.common.dto.crm;
import java.time.*;
import java.util.*;
public record CrmAvailabilityDto(UUID projectId, UUID masterId, List<LocalDateTime> starts, String zoneId,
        long contextVersion, long zoneRevision, Instant observedAt) {}
