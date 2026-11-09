package kit.penny.clientbus.common.dto.crm;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record CrmMoveAvailabilityRequest(@NotNull @PositiveOrZero Long contextVersion,
        @NotNull @PositiveOrZero Long zoneRevision, @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull LocalDate date) {}
