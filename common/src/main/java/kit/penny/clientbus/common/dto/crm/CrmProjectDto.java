package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmProjectDto(UUID id, UUID connectionId, UUID workspaceId, CrmType provider, String externalId, String displayName, String currency, String zoneId, String zoneSource, long zoneRevision, long contextVersion, boolean enabled, CrmConnectionStatus connectionState, List<CrmCapabilityDto> capabilities, long version, boolean archived) {}
