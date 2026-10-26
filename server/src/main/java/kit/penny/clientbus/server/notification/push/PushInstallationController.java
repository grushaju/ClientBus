package kit.penny.clientbus.server.notification.push;

import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.server.notification.InstallationAccessPolicy;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/push/installations")
public class PushInstallationController {
    private final PushInstallationService service;private final HttpAccessPolicy access;
    public PushInstallationController(PushInstallationService service,HttpAccessPolicy access){this.service=service;this.access=access;}
    @PutMapping("/{id}")
    public ResponseEntity<PushInstallationDto> put(@PathVariable UUID id,@Valid @RequestBody PushInstallationRequest request){
        var result=service.put(access.sessionSubject(),id,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(Long.toString(result.registrationVersion())).body(result);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,@RequestHeader("X-Installation-Secret") String proof,
                                       @RequestHeader("If-Match") String etag){
        if(!etag.matches("\"[1-9][0-9]{0,17}\""))throw new IllegalArgumentException("Invalid version");
        service.delete(access.sessionSubject(),id,proof,Long.parseLong(etag.substring(1,etag.length()-1)));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @ExceptionHandler(InstallationAccessPolicy.VersionConflict.class)
    ResponseEntity<NotificationApiErrorDto> conflict(InstallationAccessPolicy.VersionConflict error){return error(409).eTag(error.etag()).body(body(409,"INSTALLATION_CONFLICT"));}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<NotificationApiErrorDto> endpointConflict(){return error(409).body(body(409,"INSTALLATION_CONFLICT"));}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<NotificationApiErrorDto> denied(){return error(403).body(body(403,"FORBIDDEN"));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<NotificationApiErrorDto> invalid(){return error(400).body(body(400,"INVALID_REQUEST"));}
    @ExceptionHandler({PushEndpointCipher.Unavailable.class,org.springframework.dao.DataAccessException.class})
    ResponseEntity<NotificationApiErrorDto> unavailable(){return error(503).body(body(503,"UNAVAILABLE"));}
    private ResponseEntity.BodyBuilder error(int status){return ResponseEntity.status(status).cacheControl(CacheControl.noStore());}
    private NotificationApiErrorDto body(int status,String code){return new NotificationApiErrorDto(code,
        NotificationDiagnostics.apiFailure(NotificationDiagnostics.Stage.PUSH_API,status,code));}
}
