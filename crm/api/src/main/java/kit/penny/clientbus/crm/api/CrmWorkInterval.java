package kit.penny.clientbus.crm.api;

import java.time.LocalDateTime;
import java.util.Map;

/** A dated shift, not a booking slot. externalRef does not imply stable identity. */
public record CrmWorkInterval(String externalMasterId, LocalDateTime localStart, LocalDateTime localEnd,
                              String kind, String externalRef, Map<String, Object> attributes) {
    public CrmWorkInterval { attributes = attributes == null ? Map.of() : Map.copyOf(attributes); }
}
