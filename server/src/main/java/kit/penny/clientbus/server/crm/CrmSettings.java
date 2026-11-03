package kit.penny.clientbus.server.crm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public record CrmSettings(
        @Value("${clientbus.crm.secret-key:}") String secretKey,
        @Value("${clientbus.crm.secret-key-id:primary}") String secretKeyId,
        @Value("${clientbus.crm.connect-timeout-ms:5000}") long connectTimeoutMs,
        @Value("${clientbus.crm.request-timeout-ms:15000}") long requestTimeoutMs,
        @Value("${clientbus.crm.max-response-bytes:4194304}") int maxResponseBytes) {
    public CrmSettings {
        if (connectTimeoutMs < 100 || connectTimeoutMs > 30000 || requestTimeoutMs < 100 || requestTimeoutMs > 30000
                || maxResponseBytes < 1024 || maxResponseBytes > 8388608
                || secretKeyId == null || !secretKeyId.matches("[a-zA-Z0-9-]{1,64}"))
            throw new IllegalArgumentException("Invalid CRM settings");
    }
    public Duration connectTimeout() { return Duration.ofMillis(connectTimeoutMs); }
    public Duration requestTimeout() { return Duration.ofMillis(requestTimeoutMs); }
    @Override public String toString() { return "CrmSettings[redacted]"; }
}

