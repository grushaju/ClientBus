package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import kit.penny.clientbus.common.dto.crm.CrmCreateAppointmentRequest.Offer;
public record CrmAvailabilityRequest(@NotNull @PositiveOrZero Long contextVersion,
        @NotNull @PositiveOrZero Long zoneRevision, @NotNull UUID masterId,
        @NotEmpty @Size(max=10) List<@NotNull @Valid Offer> offers, @NotNull LocalDate date) {}
