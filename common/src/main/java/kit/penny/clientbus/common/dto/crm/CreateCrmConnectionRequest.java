package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CreateCrmConnectionRequest(@NotNull CrmType provider, @NotBlank @Size(max = 160) String login, @NotBlank @Size(max = 512) String secret, @Size(max = 80) String displayName) {
    public CreateCrmConnectionRequest(CrmType provider, String login, String secret) { this(provider, login, secret, null); }
    @Override public String toString() { return "CreateCrmConnectionRequest[redacted]"; }
}
