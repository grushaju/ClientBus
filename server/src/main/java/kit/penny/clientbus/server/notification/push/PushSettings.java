package kit.penny.clientbus.server.notification.push;

import kit.penny.clientbus.common.notification.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("clientbus.notifications.push")
public record PushSettings(@DefaultValue("") String endpointKey,
                           @DefaultValue List<AllowedApp> allowedApps,
                           @DefaultValue("2") int providerThreads,
                           @DefaultValue("PT5S") Duration providerTimeout) {
    public record AllowedApp(String appId, PushProviderType provider, PushEnvironment environment) {}
    public PushSettings {
        endpointKey = endpointKey == null ? "" : endpointKey;
        allowedApps = allowedApps == null ? List.of() : List.copyOf(allowedApps);
        if (providerThreads < 1 || providerThreads > 8 || providerTimeout == null
                || providerTimeout.compareTo(Duration.ofMillis(1)) < 0 || providerTimeout.compareTo(Duration.ofSeconds(20)) > 0)
            throw new IllegalArgumentException("Invalid push limits");
        for (var app : allowedApps) if (app.appId() == null || app.appId().isBlank() || app.appId().length() > 128
                || app.provider() == null || app.environment() == null) throw new IllegalArgumentException("Invalid push allowlist");
    }
    public boolean allows(String appId, PushProviderType provider, PushEnvironment environment, PushPlatform platform) {
        return platform != null && (provider != PushProviderType.APNS || platform == PushPlatform.IOS)
                && allowedApps.stream().anyMatch(app -> app.appId().equals(appId) && app.provider() == provider && app.environment() == environment);
    }
    @Override public String toString() { return "PushSettings[endpointKey=REDACTED]"; }
}
