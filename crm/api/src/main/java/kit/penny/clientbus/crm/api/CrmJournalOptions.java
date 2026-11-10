package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.UUID;

public record CrmJournalOptions(UUID projectId,long contextVersion,long zoneRevision,long epoch,
        boolean force,Instant barrier,Instant deadline) {
    public CrmJournalOptions { if(projectId==null||contextVersion<0||zoneRevision<0||epoch<0||barrier==null||deadline==null)throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}
