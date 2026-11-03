package kit.penny.clientbus.server.crm;

import java.util.UUID;

public record CrmContext(UUID projectId, UUID organizationId, UUID workspaceId, String provider,
                         String externalProjectId, UUID connectionId, long credentialVersion,
                         long contextVersion, long zoneRevision) {}

