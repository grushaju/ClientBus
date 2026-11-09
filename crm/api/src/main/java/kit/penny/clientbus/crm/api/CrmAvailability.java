package kit.penny.clientbus.crm.api;

import java.time.*;
import java.util.*;

/** Starts returned by the provider. End times and bookable slots are never inferred from shifts. */
public record CrmAvailability(String externalProjectId, String externalMasterId, List<String> externalServiceIds,
        LocalDate date, List<LocalDateTime> starts, Instant observedAt) {
    public CrmAvailability { externalServiceIds=List.copyOf(externalServiceIds); starts=List.copyOf(starts); }
}
