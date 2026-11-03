package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record ChangeCrmConnectionRequest(@NotNull UUID connectionId, @NotNull @Min(0) Long expectedVersion) {}

