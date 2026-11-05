package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.RefreshCrmRequest;
import kit.penny.clientbus.crm.api.*;
import java.time.LocalDate;
import java.util.*;

record CrmAppointmentScope(String dataset, UUID crmClientId, LocalDate dateFrom, LocalDate dateTo) {
    CrmAppointmentScope {
        if (dataset==null || !Set.of("APPOINTMENTS","CLIENT_HISTORY").contains(dataset) || !RefreshCrmRequest.validPeriod(dateFrom,dateTo)
                || "CLIENT_HISTORY".equals(dataset)!=(crmClientId!=null)) throw CrmException.invalid();
    }
    String key() { return (crmClientId==null ? "ALL" : crmClientId)+"/"+dateFrom+"/"+dateTo; }
    Map<String,Object> fields() {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("dateFrom",dateFrom.toString()); fields.put("dateTo",dateTo.toString());
        if (crmClientId!=null) fields.put("crmClientId",crmClientId.toString());
        return fields;
    }
    CrmAppointmentQuery query(CrmClientReference client) { return new CrmAppointmentQuery(dateFrom,dateTo,client); }
}
