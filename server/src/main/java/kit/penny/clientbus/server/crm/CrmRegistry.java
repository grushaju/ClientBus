package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.crm.api.CrmProvider;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CrmRegistry {
    private final Map<CrmType, CrmProvider> providers = new EnumMap<>(CrmType.class);
    public CrmRegistry(List<CrmProvider> implementations) {
        for (var provider : implementations) {
            CrmType type = CrmType.valueOf(provider.code());
            if (providers.putIfAbsent(type, provider) != null) throw new IllegalStateException("Duplicate CRM provider");
        }
        if (!providers.keySet().containsAll(AvailableCrmType.types())) throw new IllegalStateException("Missing available CRM provider");
    }
    public CrmProvider require(CrmType type) {
        if (type == null) throw CrmException.invalid();
        if (!AvailableCrmType.types().contains(type) || !providers.containsKey(type))
            throw new CrmException(409, "CRM_UNSUPPORTED", "Эта CRM пока не поддерживается.");
        return providers.get(type);
    }
    public void invalidate(UUID connectionId) { providers.values().forEach(p -> p.invalidate(connectionId)); }
}

