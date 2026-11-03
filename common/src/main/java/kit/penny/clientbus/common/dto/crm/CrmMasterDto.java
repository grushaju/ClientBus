package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmMasterDto(UUID id, UUID projectId, String externalId, String displayName, UUID employeeId, Instant mappingUpdatedAt, UUID mappingUpdatedBy, UUID mappingPreviousId, String mappingReason, Instant lastObservedAt, String observationState, long version) {}
