package kit.penny.clientbus.common.dto.crm;

import java.time.Instant;
import java.util.UUID;

public record CrmOperationDto(UUID id, UUID projectId, String type, UUID appointmentId, UUID initiatorEmployeeId,
        UUID idempotencyKey, long contextVersion, long zoneRevision, String status, Instant createdAt,
        Instant updatedAt, String errorCode, String externalAppointmentId, Instant retryAt) {
    public CrmOperationDto(UUID id, UUID projectId, String type, UUID appointmentId, UUID initiatorEmployeeId,
            UUID idempotencyKey,long contextVersion,long zoneRevision,String status,Instant createdAt,Instant updatedAt,String errorCode,String externalAppointmentId) {
        this(id,projectId,type,appointmentId,initiatorEmployeeId,idempotencyKey,contextVersion,zoneRevision,status,createdAt,updatedAt,errorCode,externalAppointmentId,null);
    }
}
