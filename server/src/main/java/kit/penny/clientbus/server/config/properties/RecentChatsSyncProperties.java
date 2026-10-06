package kit.penny.clientbus.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(
        prefix = "clientbus.sync.recent-chats"
)
public record RecentChatsSyncProperties(
        Duration interval,
        Duration lease
) {

    public RecentChatsSyncProperties {

        if (interval == null
                || interval.isZero()
                || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "Recent chats sync interval must be positive"
            );
        }

        if (lease == null
                || lease.isZero()
                || lease.isNegative()) {
            throw new IllegalArgumentException(
                    "Recent chats sync lease must be positive"
            );
        }
    }
}