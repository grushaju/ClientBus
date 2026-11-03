package kit.penny.clientbus.common.dto.crm;
import java.time.Instant;
import java.util.*;

public record CrmServiceDto(UUID id, UUID projectId, String externalId, String displayName, String category,
        Map<String, Object> attributes, List<CrmServiceOfferDto> offers, String observationState,
        Instant lastObservedAt, long version) {}
