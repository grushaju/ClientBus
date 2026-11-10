package kit.penny.clientbus.crm.api;

import java.time.LocalDate;

/** Original external observation. A new worker claim never changes this order. */
public record CrmJournalObservation(long epoch,long sequence,LocalDate day,boolean cacheHit,CrmSourceHealth health) {
    public CrmJournalObservation(long epoch,long sequence,LocalDate day,boolean cacheHit){this(epoch,sequence,day,cacheHit,null);}
}
