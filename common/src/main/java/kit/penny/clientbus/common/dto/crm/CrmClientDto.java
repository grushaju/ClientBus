package kit.penny.clientbus.common.dto.crm;
import java.time.Instant;
import java.util.*;
public record CrmClientDto(UUID id, UUID projectId, String externalId, String identityKind, String displayName,
        Map<String,String> contacts, String normalizedPhone, String avatarUrl, UUID clientId, Instant mappingUpdatedAt,
        UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, long version,
        Long visitCount, String totalSpent, String currency, String averageCheck, Instant statisticsUpdatedAt) {
    public CrmClientDto(UUID id, UUID projectId, String externalId, String identityKind, String displayName,
            Map<String,String> contacts, String normalizedPhone, String avatarUrl, UUID clientId, Instant mappingUpdatedAt,
            UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, long version,
            Long visitCount, String totalSpent, String currency, String averageCheck) {
        this(id,projectId,externalId,identityKind,displayName,contacts,normalizedPhone,avatarUrl,clientId,mappingUpdatedAt,
                mappingUpdatedBy,mappingPreviousId,mappingReason,lastObservedAt,version,visitCount,totalSpent,currency,averageCheck,null);
    }
    public CrmClientDto(UUID id, UUID projectId, String externalId, String identityKind, String displayName,
            Map<String,String> contacts, String normalizedPhone, String avatarUrl, UUID clientId, Instant mappingUpdatedAt,
            UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, long version,
            Long visitCount, String totalSpent, String currency) {
        this(id,projectId,externalId,identityKind,displayName,contacts,normalizedPhone,avatarUrl,clientId,mappingUpdatedAt,
                mappingUpdatedBy,mappingPreviousId,mappingReason,lastObservedAt,version,visitCount,totalSpent,currency,null);
    }
    public CrmClientDto(UUID id, UUID projectId, String externalId, String identityKind, String displayName,
            Map<String,String> contacts, String normalizedPhone, String avatarUrl, UUID clientId, Instant mappingUpdatedAt,
            UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, long version) {
        this(id,projectId,externalId,identityKind,displayName,contacts,normalizedPhone,avatarUrl,clientId,mappingUpdatedAt,
                mappingUpdatedBy,mappingPreviousId,mappingReason,lastObservedAt,version,null,null,null);
    }
}
