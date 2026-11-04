package kit.penny.clientbus.crm.api;

import java.util.Map;
import java.util.Set;

/** A provider identity, never an automatically created ClientBus client. */
public record CrmClient(String externalId, String identityKind, String displayName,
                        Map<String, String> contacts, Map<String, Object> profile, Set<String> presentFields) {
    public CrmClient {
        contacts = Map.copyOf(contacts); profile = Map.copyOf(profile); presentFields = Set.copyOf(presentFields);
    }
}
