package kit.penny.clientbus.server.crm;
import kit.penny.clientbus.common.dto.crm.CrmClientDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/crm") @PreAuthorize("isAuthenticated()")
public class CrmClientAvatarTransferController {
    private final CrmClientAvatarTransfer service;
    public CrmClientAvatarTransferController(CrmClientAvatarTransfer service){this.service=service;}
    @PostMapping("/clients/{id}/avatar/from-clientbus")
    public CrmClientDto transfer(@PathVariable UUID id,@RequestBody CrmClientAvatarTransfer.Request request){return service.transfer(id,request);}
}
