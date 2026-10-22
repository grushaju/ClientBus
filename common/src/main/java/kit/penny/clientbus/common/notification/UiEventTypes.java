package kit.penny.clientbus.common.notification;

import java.util.Set;

public final class UiEventTypes {
    private UiEventTypes() {}
    public static final int SCHEMA_VERSION = 1;
    public static final Set<String> ALL = Set.of(
            "stream.ready", "stream.heartbeat", "stream.resync-required",
            "messages.invalidated", "inbox.invalidated", "groups.invalidated",
            "filters.invalidated", "clients.invalidated", "channels.invalidated",
            "workspace.invalidated", "employees.invalidated", "identity.invalidated",
            "access.invalidated", "notifications.created", "notifications.invalidated",
            "history-operation.invalidated");
}
