package kit.penny.clientbus.server.notification.center;

import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.notification.NotificationApiErrorDto;
import kit.penny.clientbus.common.dto.notification.NotificationPreferencesDto;
import kit.penny.clientbus.common.dto.notification.UpdateNotificationPreferencesRequest;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/preferences")
public class NotificationPreferencesController {
    private final NotificationPreferencesService preferences;
    private final HttpAccessPolicy access;

    public NotificationPreferencesController(NotificationPreferencesService preferences, HttpAccessPolicy access) {
        this.preferences = preferences;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<NotificationPreferencesDto> get() {
        return ok(preferences.get(access.sessionSubject()));
    }

    @PutMapping
    public ResponseEntity<NotificationPreferencesDto> update(@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ok(preferences.update(access.sessionSubject(), request.soundEnabled()));
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(NotificationApiException.class)
    ResponseEntity<NotificationApiErrorDto> failure(NotificationApiException error) { return failure(error.status(), error.code()); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<NotificationApiErrorDto> forbidden() { return failure(403, "FORBIDDEN"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<NotificationApiErrorDto> unavailable() { return failure(503, "UNAVAILABLE"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<NotificationApiErrorDto> invalid() { return failure(400, "INVALID_REQUEST"); }

    private ResponseEntity<NotificationApiErrorDto> failure(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new NotificationApiErrorDto(code,
                        NotificationDiagnostics.apiFailure(NotificationDiagnostics.Stage.CENTER_API, status, code)));
    }
}
