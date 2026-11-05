package kit.penny.clientbus.common.dto.crm;
import java.util.*;

public record CrmAppointmentServiceDto(UUID id, UUID serviceId, String externalServiceId, String externalLineId,
        String displayName, Long durationSeconds, String price, String currency, Set<String> observedFields) {}
