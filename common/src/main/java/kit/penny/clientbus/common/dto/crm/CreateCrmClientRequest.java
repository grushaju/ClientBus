package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import java.util.List;
public record CreateCrmClientRequest(@NotBlank @Size(max=50) String firstName, @NotNull @Size(max=50) String lastName,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=20) String> phoneList, @NotNull @Min(0) Long expectedVersion) {}
