package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record LinkCrmClientRequest(@NotNull UUID clientId, @NotNull @Min(0) Long expectedVersion) {}
