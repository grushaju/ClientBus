package kit.penny.clientbus.server.notification.history;

import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class HistorySyncController {
    private final HistorySyncService history;
    private final HttpAccessPolicy access;
    public HistorySyncController(HistorySyncService history, HttpAccessPolicy access) { this.history = history; this.access = access; }

    @PostMapping("/conversations/{id}/history-sync")
    public ResponseEntity<HistorySyncOperationDto> submit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody HistorySyncRequest request) {
        var result = history.submit(access.sessionSubject(), id, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/history-sync-operations/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/history-sync-operations/{id}")
    public ResponseEntity<HistorySyncOperationDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.get(access.sessionSubject(), id));
    }
    @GetMapping("/conversations/{id}/history-sync-operations")
    public ResponseEntity<HistorySyncOperationPageDto> list(@PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean activeOnly, @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) String pageCursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.list(access.sessionSubject(), id, activeOnly, limit, pageCursor));
    }
    @ExceptionHandler(NotificationApiException.class)
    ResponseEntity<NotificationApiErrorDto> failure(NotificationApiException error) { return failure(error.status(), error.code()); }
    @ExceptionHandler({DataAccessException.class, ChannelUnavailableException.class})
    ResponseEntity<NotificationApiErrorDto> unavailable() { return failure(503, "UNAVAILABLE"); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<NotificationApiErrorDto> forbidden() { return failure(403, "FORBIDDEN"); }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<NotificationApiErrorDto> invalid() { return failure(400, "INVALID_REQUEST"); }
    private ResponseEntity<NotificationApiErrorDto> failure(int status, String code) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 429 || status == 503) response.header(HttpHeaders.RETRY_AFTER, "2");
        return response.body(new NotificationApiErrorDto(code,
                NotificationDiagnostics.apiFailure(NotificationDiagnostics.Stage.HISTORY_API, status, code)));
    }
}
