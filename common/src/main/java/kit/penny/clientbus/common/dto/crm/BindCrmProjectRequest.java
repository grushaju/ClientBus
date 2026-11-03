package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record BindCrmProjectRequest(@NotNull UUID connectionId, @NotBlank @Size(max = 128) String externalProjectId, @Size(max = 128) String zoneId) {}

