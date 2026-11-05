package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.CrmWorkIntervalPageDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/crm/projects/{projectId}/work-intervals")
@PreAuthorize("isAuthenticated()")
public class CrmScheduleController {
    private final CrmScheduleReader reader;
    public CrmScheduleController(CrmScheduleReader reader) { this.reader=reader; }
    @GetMapping
    public CrmWorkIntervalPageDto intervals(@PathVariable UUID projectId,@RequestParam UUID masterId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,@RequestParam(required=false) String cursor) {
        new CrmScheduleScope(masterId,dateFrom,dateTo);
        return reader.intervals(projectId,masterId,dateFrom,dateTo,cursor);
    }
}
