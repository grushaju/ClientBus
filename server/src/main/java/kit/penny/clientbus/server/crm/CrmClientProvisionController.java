package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/crm") @PreAuthorize("isAuthenticated()")
public class CrmClientProvisionController {
    private final CrmClientProvisioning service;
    public CrmClientProvisionController(CrmClientProvisioning service){this.service=service;}
    @GetMapping("/projects/{id}/client-search")
    public CrmClientSearchDto search(@PathVariable UUID id,@RequestParam UUID clientId,@RequestParam String q){return service.search(id,clientId,q);}
    @PostMapping("/projects/{id}/client-provisions")
    public ResponseEntity<CrmClientProvisionDto> create(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody CrmProvisionClientRequest request){
        return ResponseEntity.accepted().body(service.create(id,key,request));
    }
    @GetMapping("/projects/{id}/client-provisions/latest")
    public Latest latest(@PathVariable UUID id,@RequestParam UUID clientId){return new Latest(service.latest(id,clientId));}
    public record Latest(CrmClientProvisionDto operation) {}
    public record Resolution(UUID crmClientId,Long expectedVersion) {}
    @PostMapping("/client-provisions/{id}/resolve")
    public CrmClientProvisionDto resolve(@PathVariable UUID id,@RequestBody Resolution request){
        if(request==null || request.crmClientId()==null || request.expectedVersion()==null || request.expectedVersion()<0)throw CrmException.invalid();
        return service.resolve(id,request.crmClientId(),request.expectedVersion());
    }
    @PostMapping("/client-provisions/{id}/recover")
    public CrmClientProvisionDto recover(@PathVariable UUID id){return service.recover(id);}
}
