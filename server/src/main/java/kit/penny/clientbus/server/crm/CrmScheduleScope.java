package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.RefreshCrmRequest;
import kit.penny.clientbus.crm.api.CrmWorkIntervalQuery;
import java.time.LocalDate;
import java.util.*;

record CrmScheduleScope(UUID masterId, LocalDate dateFrom, LocalDate dateTo) {
    CrmScheduleScope {
        if (masterId == null || !RefreshCrmRequest.validPeriod(dateFrom, dateTo)) throw CrmException.invalid();
    }
    String key() { return masterId + "/" + dateFrom + "/" + dateTo; }
    Map<String, Object> fields() { return Map.of("masterId", masterId.toString(), "dateFrom", dateFrom.toString(), "dateTo", dateTo.toString()); }
    CrmWorkIntervalQuery query(String externalId) { return new CrmWorkIntervalQuery(externalId, dateFrom, dateTo); }
}
