package kit.penny.clientbus.crm.api;

import java.util.Map;

public record CrmCatalogService(String externalId, String displayName, String category, Map<String, Object> attributes) {
    public CrmCatalogService { attributes = Map.copyOf(attributes); }
}
