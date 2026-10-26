package kit.penny.clientbus.common.dto.notification;

/** Account preference, independent of the currently selected workspace or browser. */
public record NotificationPreferencesDto(boolean soundEnabled) {
}
