package kit.penny.clientbus.common.dto.crm;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public record CrmChangeAppointmentRequest(@NotNull @PositiveOrZero Long contextVersion,
        @NotNull @PositiveOrZero Long zoneRevision, @NotNull @PositiveOrZero Long expectedVersion,
        LocalDateTime localStart, @Size(max=2000) String reason) {
    @Override public String toString() { return "CrmChangeAppointmentRequest[redacted]"; }
}
