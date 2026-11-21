package kit.penny.clientbus.crm.api;

/** A one-time contact snapshot explicitly confirmed by an employee. */
public record CrmClientDraft(String firstName, String lastName, String phone) {
    @Override public String toString() { return "CrmClientDraft[redacted]"; }
}
