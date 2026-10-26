package kit.penny.clientbus.common.dto.notification;

import jakarta.validation.constraints.NotNull;

/** Boxed value rejects both an omitted property and an explicit null. */
public record UpdateNotificationPreferencesRequest(@NotNull Boolean soundEnabled) {
}
