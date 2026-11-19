package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import kit.penny.clientbus.server.security.SseRequestSecurity;
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
    public ResponseEntity<SseEmitter> events(@RequestParam(required=false) UUID workspaceId,
            @RequestParam(required=false) String tab,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        var emitter = gateway.open(access.sessionSubject(),workspaceId,response,streamTab(tab));
        request.setAttribute(SseRequestSecurity.ADMITTED, true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
                .body(emitter);
    }
    /**
     * Идентификатор вкладки — строка клиента, доверия к ней нет: она служит только ключом
     * «это моё же переподключение». Наружу проходит ограниченная форма, иначе произвольным
     * содержимым наполняются логи.
     */
    private static String streamTab(String tab) {
        return tab != null && tab.matches("[A-Za-z0-9_-]{8,64}") ? tab : null;
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
        var body = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        // Собственный 429 обязан называть срок: без него клиент удваивает свою экспоненту и
        // вереницей стучится в тот же отказ.
        if (status==429) body = body.header("Retry-After",String.valueOf(gateway.retryAfterSeconds()));
        return body.body(new NotificationApiErrorDto(code,UUID.randomUUID()));
    }
}
