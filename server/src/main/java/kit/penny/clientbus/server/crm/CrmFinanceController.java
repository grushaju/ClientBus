package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.CrmFinanceDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/crm/appointments/{id}/finance")
@PreAuthorize("isAuthenticated()")
public class CrmFinanceController {
    private final CrmFinanceService service;
    public CrmFinanceController(CrmFinanceService service) { this.service=service; }
    @GetMapping public CrmFinanceDto read(@PathVariable UUID id) { return service.read(id); }
    @PostMapping("/refresh") public CrmFinanceDto refresh(@PathVariable UUID id) { return service.refresh(id); }
}
