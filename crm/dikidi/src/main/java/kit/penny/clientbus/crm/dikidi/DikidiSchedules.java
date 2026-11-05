package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** R03 only. Online availability/timeworks never replace the owner schedule. */
final class DikidiSchedules {
    static CrmScheduleSnapshot parse(JsonNode root, String project, CrmWorkIntervalQuery query) {
        JsonNode schedules = root.path("masters").path("schedules"), data = schedules.path("data");
        if (!schedules.isObject() || !(data.isObject() || data.isArray() && data.isEmpty()))
            throw new CrmFailure(INVALID_RESPONSE);
        JsonNode dates = data.path(query.externalMasterId());
        if (!dates.isMissingNode() && !(dates.isObject() || dates.isArray() && dates.isEmpty()))
            throw new CrmFailure(INVALID_RESPONSE);
        List<CrmWorkInterval> items = new ArrayList<>();
        try {
            for (var entry : dates.properties()) {
                LocalDate date = LocalDate.parse(entry.getKey());
                if (date.isBefore(query.dateFrom()) || date.isAfter(query.dateTo())) continue;
                JsonNode row = entry.getValue();
                if (!row.isObject()) throw new CrmFailure(INVALID_RESPONSE);
                if (row.has("master_id") && !query.externalMasterId().equals(row.path("master_id").asString("")))
                    throw new CrmFailure(CONTEXT_MISMATCH);
                if (row.has("date") && !entry.getKey().equals(row.path("date").asString("")))
                    throw new CrmFailure(CONTEXT_MISMATCH);
                // Multiple shifts, overnight boundaries and nonempty breaks have not been proven by A06.
                for (String field : List.of("break_from", "break_to")) {
                    JsonNode value = row.path(field);
                    if (!value.isMissingNode() && !value.isNull()
                            && !(value.isString() && Set.of("", "00:00", "00:00:00").contains(value.asString())))
                        throw new CrmFailure(CAPABILITY_UNVERIFIED);
                }
                JsonNode from = row.path("work_from"), to = row.path("work_to");
                if (!from.isString() || !to.isString()) throw new CrmFailure(INVALID_RESPONSE);
                LocalTime start = LocalTime.parse(from.asString()), end = LocalTime.parse(to.asString());
                if (!end.isAfter(start)) throw new CrmFailure(CAPABILITY_UNVERIFIED);
                items.add(new CrmWorkInterval(query.externalMasterId(), date.atTime(start), date.atTime(end),
                        "WORK", null, Map.of("source", "DIKIDI_R03")));
                if (items.size() > 1000) throw new CrmFailure(INVALID_RESPONSE);
            }
        } catch (DateTimeParseException error) { throw new CrmFailure(INVALID_RESPONSE); }
        // R03 positive dated observations are proven, completeness of missing dates is not.
        return new CrmScheduleSnapshot(items, Instant.now(), CrmReadResult.Completeness.UNKNOWN, project, query);
    }
    private DikidiSchedules() {}
}
