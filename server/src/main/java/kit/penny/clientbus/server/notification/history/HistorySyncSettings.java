package kit.penny.clientbus.server.notification.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("clientbus.notifications.history")
public record HistorySyncSettings(@DefaultValue("4") int workers, @DefaultValue("4") int maxActivePerEmployee,
        @DefaultValue("4") int maxActivePerConversation, @DefaultValue("3") int maxAttempts) {
    public HistorySyncSettings {
        if (workers < 1 || workers > 32 || maxActivePerEmployee < 1 || maxActivePerConversation < 1 || maxAttempts < 1)
            throw new IllegalArgumentException("Invalid history limits");
    }
}
