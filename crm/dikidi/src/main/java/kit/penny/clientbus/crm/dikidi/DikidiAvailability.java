package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** R17: explicit date only. Working hours and occupied intervals never manufacture free starts. */
final class DikidiAvailability {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    static CrmAvailability parse(JsonNode root,String project,String master,List<String> services,LocalDate date,Instant observedAt) {
        if (root==null || !root.isObject() || date==null || observedAt==null || !root.path("dates_true").isArray()
                || !root.path("appointments").isContainer() || !root.path("timeworks").isContainer()) fail(INVALID_RESPONSE);
        JsonNode times=root.path("times");
        if (!(times.isObject() || times.isArray() && times.isEmpty())) fail(INVALID_RESPONSE);
        if (times.properties().stream().anyMatch(entry -> !entry.getKey().equals(master))) fail(CONTEXT_MISMATCH);
        var days=new HashSet<LocalDate>();
        for (var day:root.path("dates_true")) {
            if (!day.isString()) fail(INVALID_RESPONSE);
            try { days.add(LocalDate.parse(day.asString())); } catch(DateTimeException e) { fail(INVALID_RESPONSE); }
        }
        var values=times.path(master); var starts=new TreeSet<LocalDateTime>();
        if (!values.isMissingNode()) {
            if (!values.isArray() || values.size()>5000) fail(INVALID_RESPONSE);
            for (var value:values) {
                if (!value.isString()) fail(INVALID_RESPONSE);
                LocalDateTime start;
                try { start=LocalDateTime.parse(value.asString(),TIME); } catch(DateTimeException e) { throw new CrmFailure(INVALID_RESPONSE); }
                if (!start.toLocalDate().equals(date) || !days.contains(date)) fail(CONTEXT_MISMATCH);
                starts.add(start);
            }
        }
        return new CrmAvailability(project,master,services,date,List.copyOf(starts),observedAt);
    }
    private static void fail(CrmFailure.Code code) { throw new CrmFailure(code); }
}
