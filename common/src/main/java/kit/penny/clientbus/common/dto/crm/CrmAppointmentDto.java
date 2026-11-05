package kit.penny.clientbus.common.dto.crm;
import java.time.*;
import java.util.*;

public record CrmAppointmentDto(UUID id, UUID projectId, UUID workspaceId, String externalId, String kind,
        UUID crmClientId, UUID clientId, String clientIdentityKind, String externalClientId, Map<String,Object> clientSnapshot,
        LocalDateTime localStart, LocalDateTime localEnd, String zoneId, long zoneRevision, Long derivedZoneRevision,
        Instant startAt, Instant endAt, String appointmentState, String confirmationState, String attendanceState,
        String paymentState, Map<String,String> providerState, String comment, List<CrmAppointmentServiceDto> services,
        List<CrmAppointmentMasterDto> masters, boolean servicesComplete, boolean mastersComplete,
        String observationState, Instant lastObservedAt, Instant observationCheckedAt,
        boolean requiresVerification, List<String> warnings, Set<String> observedFields, String source, long version) {}
