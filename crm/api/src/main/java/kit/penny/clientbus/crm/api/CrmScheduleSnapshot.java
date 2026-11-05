package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** COMPLETE authorizes replacement only within this exact master/date scope. */
public record CrmScheduleSnapshot(List<CrmWorkInterval> items, Instant observedAt,
                                  CrmReadResult.Completeness completeness, String externalProjectId,
                                  CrmWorkIntervalQuery scope) {
    public CrmScheduleSnapshot { items = List.copyOf(items); }
}
