package kit.penny.clientbus.common.dto.crm;
import java.time.*;
import java.util.*;

public record CrmAppointmentMasterDto(UUID id, UUID masterId, String externalMasterId, String displayName,
        LocalDateTime localStart, LocalDateTime localEnd, Long durationSeconds, String price, String currency,
        Set<String> observedFields) {}
