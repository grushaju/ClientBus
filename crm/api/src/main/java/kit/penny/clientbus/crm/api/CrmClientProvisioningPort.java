package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public interface CrmClientProvisioningPort {
    record Search(List<CrmClient> items, boolean hasMore) {
        public Search { items = List.copyOf(items); }
    }
    default boolean canCreateClient() { return false; }
    default Search findClients(CrmAccess access, String project, String query, Instant until) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** The callback commits a provider-confirmed identity before further external reads. No write retries. */
    default void createClient(CrmAccess access, String project, CrmClientDraft draft, Instant until, Consumer<CrmClient> confirmed) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
    /** One explicit photo replacement. beforeSave rechecks the local association before the profile write. */
    default String transferClientAvatar(CrmAccess access, String project, String client, byte[] png, Instant until, Runnable beforeSave) {
        throw new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED);
    }
}
