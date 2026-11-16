package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmMasterDto(UUID id, UUID projectId, String externalId, String displayName, boolean isMaster, UUID employeeId, Instant mappingUpdatedAt, UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, String observationState, long version,
        UUID linkedId, String linkedExternalId, String linkedDisplayName, Instant linkUpdatedAt) {}
