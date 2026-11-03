package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record UpdateCrmConnectionRequest(@NotNull @Min(0) Long expectedVersion, @Size(min = 1, max = 160) String login, @Size(min = 1, max = 512) String secret, Boolean enabled, @Size(max = 80) String displayName) {
    public UpdateCrmConnectionRequest(Long version, String login, String secret, Boolean enabled) { this(version, login, secret, enabled, null); }
    @Override public String toString() { return "UpdateCrmConnectionRequest[redacted]"; }
}
