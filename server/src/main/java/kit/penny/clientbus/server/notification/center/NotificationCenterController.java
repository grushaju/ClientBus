package kit.penny.clientbus.server.notification.center;

import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationCenterController {
    private final NotificationCenterService center;
    private final HttpAccessPolicy access;

    public NotificationCenterController(NotificationCenterService center, HttpAccessPolicy access) {
        this.center = center; this.access = access;
    }

    @GetMapping
    public ResponseEntity<NotificationPageDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") int limit, @RequestParam(required = false) String pageCursor) {
        return ok(center.list(access.sessionSubject(), unreadOnly, limit, pageCursor));
    }

    @GetMapping("/summary")
    public ResponseEntity<NotificationSummaryDto> summary() { return ok(center.summary(access.sessionSubject())); }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationDto> get(@PathVariable UUID id) { return ok(center.get(access.sessionSubject(), id)); }

    @PutMapping("/{id}/read")
    public ResponseEntity<NotificationDto> read(@PathVariable UUID id) { return ok(center.read(access.sessionSubject(), id)); }

    @PutMapping("/read-all")
    public ResponseEntity<NotificationReadAllResultDto> readAll(@RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw new NotificationApiException(400, "INVALID_REQUEST");
        return ok(center.readAll(access.sessionSubject(), key));
    }

    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    @ExceptionHandler(NotificationApiException.class)
    ResponseEntity<NotificationApiErrorDto> failure(NotificationApiException error) { return failure(error.status(), error.code()); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<NotificationApiErrorDto> forbidden() { return failure(403, "FORBIDDEN"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<NotificationApiErrorDto> unavailable() { return failure(503, "UNAVAILABLE"); }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<NotificationApiErrorDto> invalid() { return failure(400, "INVALID_REQUEST"); }

    private ResponseEntity<NotificationApiErrorDto> failure(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new NotificationApiErrorDto(code, UUID.randomUUID()));
    }
}
