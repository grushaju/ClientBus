package kit.penny.clientbus.common.dto.crm;

import java.util.UUID;

public record CrmEmployeeCandidateDto(UUID id, String displayName, String username, boolean enabled) {}
