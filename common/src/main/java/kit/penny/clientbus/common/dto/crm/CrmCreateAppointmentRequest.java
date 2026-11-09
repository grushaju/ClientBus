package kit.penny.clientbus.common.dto.crm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;

public record CrmCreateAppointmentRequest(@NotNull @PositiveOrZero Long contextVersion,
        @NotNull @PositiveOrZero Long zoneRevision, @NotNull UUID crmClientId, @NotNull UUID masterId,
        @NotEmpty @Size(max=10) List<@NotNull @Valid Offer> offers, @NotNull LocalDateTime localStart,
        @Size(max=2000) String comment) {
    public record Offer(@NotNull UUID id, @NotNull @PositiveOrZero Long expectedVersion) {}
    @Override public String toString() { return "CrmCreateAppointmentRequest[redacted]"; }
}
