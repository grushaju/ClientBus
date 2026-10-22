package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;
import java.util.UUID;
import kit.penny.clientbus.common.notification.PushPermission;

public record PushInstallationDto(
        UUID installationId, long registrationVersion, long bindingVersion, long endpointVersion,
        boolean enabled, PushPermission reportedPermission, Instant lastSeenAt
) {
}
