package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.common.dto.notification.NotificationApiErrorDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Authenticated rollback response: an absent stream must not become an ERROR dispatch and a false 401. */
@RestController
@ConditionalOnProperty(prefix = "clientbus.notifications", name = "sse-enabled", havingValue = "false", matchIfMissing = true)
public class UiEventsDisabledController {
    @GetMapping("/api/events")
    public ResponseEntity<NotificationApiErrorDto> events() {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new NotificationApiErrorDto("SSE_DISABLED", UUID.randomUUID()));
    }
}
