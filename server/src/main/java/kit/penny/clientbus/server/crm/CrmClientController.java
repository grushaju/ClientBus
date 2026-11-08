package kit.penny.clientbus.server.crm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kit.penny.clientbus.common.dto.crm.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/crm") @PreAuthorize("isAuthenticated()")
public class CrmClientController {
    private final CrmClientService service;
    public CrmClientController(CrmClientService service) { this.service=service; }
    @GetMapping("/projects/{id}/clients")
    public CrmClientPageDto clients(@PathVariable UUID id,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="false") boolean unlinked,@RequestParam(required=false) String cursor) {
        return service.clients(id,q,unlinked,cursor);
    }
    @GetMapping("/clients/{id}") public CrmClientDto client(@PathVariable UUID id) { return service.client(id); }
    @GetMapping("/clientbus-clients/{id}/links")
    public List<CrmClientLinkDto> links(@PathVariable UUID id,@RequestParam(required=false) UUID workspaceId) {
        return service.links(id,workspaceId);
    }
    @GetMapping("/clients/{id}/client-candidates")
    public List<CrmClientCandidateDto> candidates(@PathVariable UUID id,@RequestParam(required=false) String q) { return service.candidates(id,q); }
    @PutMapping("/clients/{id}/client-link")
    public CrmClientDto link(@PathVariable UUID id,@Valid @RequestBody LinkCrmClientRequest request) {
        return service.link(id,request.clientId(),request.expectedVersion());
    }
    @DeleteMapping("/clients/{id}/client-link")
    public CrmClientDto unlink(@PathVariable UUID id,@RequestParam @Min(0) long expectedVersion) { return service.link(id,null,expectedVersion); }
    @PostMapping("/clients/{id}/client")
    public ResponseEntity<CrmClientDto> create(@PathVariable UUID id,@Valid @RequestBody CreateCrmClientRequest request) {
        return ResponseEntity.status(201).body(service.create(id,request));
    }
}
