package kit.penny.clientbus.crm.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Validated server snapshot. Credentials and provider HTTP forms never belong here. */
public record CrmCommand(Type type, UUID operationId, UUID idempotencyKey, String externalAppointmentId,
        CrmClientReference client, String externalMasterId, List<Offer> offers,
        LocalDateTime localStart, String zoneId, String comment, String preservedSnapshot) {
    public enum Type { CREATE, MOVE, CANCEL }
    public record Offer(String externalServiceId, BigDecimal price, String currency, long durationSeconds) {}
    public CrmCommand { offers=List.copyOf(offers); Objects.requireNonNull(preservedSnapshot); }
    public CrmCommand withExternalReference(String reference) {
        return new CrmCommand(type,operationId,idempotencyKey,reference,client,externalMasterId,offers,localStart,zoneId,comment,preservedSnapshot);
    }
    public CrmCommand withPreservedSnapshot(String snapshot) {
        return new CrmCommand(type,operationId,idempotencyKey,externalAppointmentId,client,externalMasterId,offers,localStart,zoneId,comment,snapshot);
    }
    @Override public String toString() { return "CrmCommand[type="+type+", operationId="+operationId+"]"; }
}
