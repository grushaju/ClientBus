package kit.penny.clientbus.common.dto.crm;
import java.util.*;

public record CrmAppointmentCoverageDto(UUID projectId, UUID workspaceId, List<CrmSyncStateDto> scopes) {}
