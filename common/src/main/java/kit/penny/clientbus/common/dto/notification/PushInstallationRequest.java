package kit.penny.clientbus.common.dto.notification;

import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.notification.*;

public record PushInstallationRequest(
        @NotNull @Min(0) Long expectedRegistrationVersion,
        @NotNull PushPlatform platform,
        @NotNull PushProviderType provider,
        @NotBlank @Size(max = 128) String appId,
        @NotNull PushEnvironment environment,
        @NotBlank @Size(max = 4096) String registrationReference,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43,128}") String installationSecret,
        boolean enabled,
        @NotNull PushPermission reportedPermission
) {
    // Provider references and installation proof must never appear in logs.
    @Override
    public String toString() {
        return "PushInstallationRequest[credentials=REDACTED]";
    }
}
