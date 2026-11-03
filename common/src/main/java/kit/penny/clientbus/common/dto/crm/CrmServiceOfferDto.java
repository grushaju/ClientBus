package kit.penny.clientbus.common.dto.crm;
import java.time.Instant;
import java.util.*;

public record CrmServiceOfferDto(UUID id, UUID masterId, String externalMasterId, UUID serviceId,
        String price, String currency, Long durationSeconds, Map<String, Object> offerState,
        Map<String, Object> attributes, String observationState, Instant lastObservedAt, List<String> warnings, long version, String masterDisplayName) {}
