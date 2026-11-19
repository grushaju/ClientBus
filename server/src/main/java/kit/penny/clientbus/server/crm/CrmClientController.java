package kit.penny.clientbus.server.crm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/crm") @PreAuthorize("isAuthenticated()")
public class CrmClientController {
    private final CrmClientService service;
    private final IAttachmentStorage storage;
    public CrmClientController(CrmClientService service, IAttachmentStorage storage) {
        this.service=service; this.storage=storage;
    }
    @GetMapping("/projects/{id}/clients")
    public CrmClientPageDto clients(@PathVariable UUID id,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="false") boolean unlinked,@RequestParam(required=false) String cursor) {
        return service.clients(id,q,unlinked,cursor);
    }
    @GetMapping("/clients/{id}") public CrmClientDto client(@PathVariable UUID id) { return service.client(id); }
    /** Байты зеркала аватара карточки. Права — как у чтения карточки; нет файла — 404, карточка рисуется с инициалом. */
    @GetMapping("/clients/{id}/avatar/content")
    public ResponseEntity<InputStreamResource> avatar(@PathVariable UUID id,
            @RequestHeader(name = "If-None-Match", required = false) String match) {
        var key = service.client(id).avatarUrl();
        if (key == null || key.isBlank() || !storage.exists(key)) return ResponseEntity.notFound().build();
        // Ключ хранилища меняется вместе с файлом, поэтому он и есть эталон: новое фото отдаётся
        // сразу, а прежнее подтверждается 304 без пересылки байтов.
        var etag = "\"" + key + "\"";
        if (etag.equals(match)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).eTag(etag)
                    .contentType(MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM))
                    .body(new InputStreamResource(storage.load(key)));
        } catch (RuntimeException vanished) {
            // Между exists и load объект мог исчезнуть (сборщик осиротевших файлов 13.5): это тоже 404.
            return ResponseEntity.notFound().build();
        }
    }
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
