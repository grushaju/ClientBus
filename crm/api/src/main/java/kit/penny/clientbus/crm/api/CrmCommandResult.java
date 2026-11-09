package kit.penny.clientbus.crm.api;

/** Only a proved final state may be CONFIRMED/REJECTED. A missing row is not negative evidence. */
public record CrmCommandResult(Outcome outcome, String externalAppointmentId) {
    public enum Outcome { CONFIRMED, REJECTED, UNKNOWN }
}
