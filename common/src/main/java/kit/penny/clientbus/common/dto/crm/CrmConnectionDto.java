package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmConnectionDto(UUID id, CrmType provider, String loginHint, boolean credentialConfigured, CrmConnectionStatus status, Instant lastCheckedAt, String errorCode, long version, String displayName) {
    public CrmConnectionDto(UUID id, CrmType provider, String loginHint, boolean credentialConfigured, CrmConnectionStatus status, Instant lastCheckedAt, String errorCode, long version) {
        this(id, provider, loginHint, credentialConfigured, status, lastCheckedAt, errorCode, version, null);
    }
}
