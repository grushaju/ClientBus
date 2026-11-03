package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record UpdateCrmProjectRequest(@NotNull @Min(0) Long expectedVersion, Boolean enabled, @Size(max = 128) String zoneId) {}

