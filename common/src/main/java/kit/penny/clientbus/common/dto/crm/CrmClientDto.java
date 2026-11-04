package kit.penny.clientbus.common.dto.crm;
import java.time.Instant;
import java.util.*;
public record CrmClientDto(UUID id, UUID projectId, String externalId, String identityKind, String displayName,
        Map<String,String> contacts, String normalizedPhone, UUID clientId, Instant mappingUpdatedAt,
        UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, long version) {}
