package kit.penny.clientbus.crm.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public record CrmServiceOffer(String externalMasterId, String externalServiceId, BigDecimal price,
        String currency, Long durationSeconds, Map<String, Object> offerState, Map<String, Object> attributes,
        Set<String> presentFields) {
    public CrmServiceOffer { offerState = Map.copyOf(offerState); attributes = Map.copyOf(attributes); presentFields = Set.copyOf(presentFields); }
}
