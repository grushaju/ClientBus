package kit.penny.clientbus.crm.api;

import java.util.UUID;

/** Observation order and mutation barrier shared by server processes; unrelated to cursor revision. */
public interface CrmJournalPort {
    long epoch(UUID projectId);
    long begin(UUID projectId,long expectedEpoch);
    void invalidate(UUID projectId);
    /** Reserve a check only for a real source load, not for a cache hit or a joining waiter. */
    default CrmSourceHealth health(UUID connectionId,long credentials) { return null; }
    default boolean current(CrmJournalOptions options,UUID connectionId,long credentials) { return epoch(options.projectId())==options.epoch(); }
}
