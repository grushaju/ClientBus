package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.UUID;
import kit.penny.clientbus.common.dto.notification.NotificationApiErrorDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(prefix="clientbus.notifications",name="sse-enabled",havingValue="true")
public class UiEventsController {
    private final UiSseGateway gateway; private final HttpAccessPolicy access;
    public UiEventsController(UiSseGateway gateway,HttpAccessPolicy access) { this.gateway=gateway;this.access=access; }
    @GetMapping(value="/api/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@RequestParam(required=false) UUID workspaceId, jakarta.servlet.http.HttpServletResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
                .body(gateway.open(access.sessionSubject(),workspaceId,response));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<NotificationApiErrorDto> status(ResponseStatusException error) {
        int status=error.getStatusCode().value();
        return failure(status,switch(status){case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 429->"RATE_LIMITED";default->"UNAVAILABLE";});
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<NotificationApiErrorDto> denied() { return failure(403,"FORBIDDEN"); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<NotificationApiErrorDto> unavailable() { return failure(503,"UNAVAILABLE"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<NotificationApiErrorDto> invalid() { return failure(400,"INVALID_REQUEST"); }
    private ResponseEntity<NotificationApiErrorDto> failure(int status,String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new NotificationApiErrorDto(code,UUID.randomUUID()));
    }
}
