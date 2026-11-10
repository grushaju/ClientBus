package kit.penny.clientbus.crm.api;

/** Only a proved final state may be CONFIRMED/REJECTED. A missing row is not negative evidence. */
public record CrmCommandResult(Outcome outcome,String externalAppointmentId,String errorCode,java.time.Instant retryAt) {
    public CrmCommandResult(Outcome outcome,String externalAppointmentId){this(outcome,externalAppointmentId,null,null);}
    public enum Outcome { CONFIRMED, REJECTED, UNKNOWN }
}
