package kit.penny.clientbus.crm.api;

import java.util.Map;

public record CrmMaster(String externalId, String displayName, Map<String, String> attributes) {
    public CrmMaster { attributes = Map.copyOf(attributes); }
}

