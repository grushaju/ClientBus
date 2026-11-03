package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.CrmServicePageDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/crm/projects/{projectId}")
@PreAuthorize("isAuthenticated()")
public class CrmCatalogController {
    private final CrmCatalogReader catalog;
    public CrmCatalogController(CrmCatalogReader catalog) { this.catalog = catalog; }
    @GetMapping("/services")
    public CrmServicePageDto services(@PathVariable UUID projectId, @RequestParam(required = false) UUID masterId,
                                     @RequestParam(required = false) UUID serviceId,
                                     @RequestParam(defaultValue = "false") boolean includeNotObserved,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) String q) {
        return catalog.services(projectId, masterId, serviceId, includeNotObserved, cursor, q);
    }
}
