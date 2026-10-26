package kit.penny.clientbus.server.notification.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kit.penny.clientbus.common.dto.notification.NotificationApiErrorDto;
import kit.penny.clientbus.server.notification.InstallationAccessPolicy;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.center.NotificationCenterController;
import kit.penny.clientbus.server.notification.history.HistorySyncController;
import kit.penny.clientbus.server.notification.push.PushInstallationController;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.concurrent.Executors;

import static kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationDiagnostics.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach void capture() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach void restore() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test void errorCorrelationIsSearchableButUntrustedErrorCodeIsNeverLogged() {
        String hostile = "secret-token\r\nendpoint=https://private.example/device?token=secret&message=private-text";
        UUID id = apiFailure(Stage.HISTORY_API, 503, hostile);
        var values = fields(appender.list.getFirst());
        assertEquals(id, values.get("correlationId"));
        assertEquals(503, values.get("status"));
        assertEquals(Code.INTERNAL_ERROR, values.get("code"));
        assertEquals(Stage.HISTORY_API, values.get("stage"));
        assertEquals(Outcome.REJECTED, values.get("outcome"));
        assertNull(appender.list.getFirst().getThrowableProxy());
        String rendered = appender.list.getFirst().getFormattedMessage() + values;
        for (String secret : List.of("secret-token", "private.example", "private-text", "\r", "\n")) {
            assertFalse(rendered.contains(secret), rendered);
        }
    }

    @Test void reusedExecutorDoesNotCarryIdentifiersToTheNextTask() throws Exception {
        UUID event = UUID.randomUUID(), correlation = UUID.randomUUID(), operation = UUID.randomUUID();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> event(Stage.HISTORY_RELAY, Outcome.PUBLISHED, Code.NONE,
                    event, correlation, operation)).get();
            executor.submit(() -> event(Stage.HISTORY_RELAY, Outcome.FAILED, Code.DB_UNAVAILABLE,
                    null, null, null)).get();
        }
        assertEquals(2, appender.list.size());
        assertEquals(operation, fields(appender.list.getFirst()).get("operationId"));
        assertEquals(Set.of("stage", "outcome", "code"), fields(appender.list.getLast()).keySet());
    }

    @Test void concurrentOperationsKeepTheirOwnIdentifiers() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                for (int i = 0; i < 20; i++) event(Stage.HISTORY_WORKER, Outcome.STARTED, Code.NONE,
                        first, first, first);
            });
            var two = executor.submit(() -> {
                for (int i = 0; i < 20; i++) event(Stage.HISTORY_WORKER, Outcome.SUCCEEDED, Code.NONE,
                        second, second, second);
            });
            one.get(); two.get();
        }
        assertEquals(40, appender.list.size());
        for (var entry : appender.list) {
            var values = fields(entry);
            assertTrue(Set.of(first, second).contains(values.get("operationId")));
            assertEquals(values.get("eventId"), values.get("correlationId"));
            assertEquals(values.get("eventId"), values.get("operationId"));
        }
    }

    @Test void pushIdentifiersHaveExplicitNamesAndCannotBeConfusedWithOperationIdentifiers() {
        UUID notification = UUID.randomUUID(), delivery = UUID.randomUUID();
        push(Stage.PUSH_WORKER, Outcome.ACCEPTED, Code.PROVIDER_ACCEPTED, notification, delivery);
        var values = fields(appender.list.getFirst());
        assertEquals(notification, values.get("notificationId"));
        assertEquals(delivery, values.get("deliveryId"));
        assertFalse(values.containsKey("operationId"));
        assertFalse(values.containsKey("correlationId"));
    }

    @Test void historyErrorPreservesWireContractAndLogsReturnedCorrelation() throws Exception {
        var response = invoke(new HistorySyncController(null, null), "failure",
                NotificationApiException.class, new NotificationApiException(429, "RATE_LIMITED"));
        assertEquals(429, response.getStatusCode().value());
        assertEquals("2", response.getHeaders().getFirst("Retry-After"));
        assertApiCorrelation(response, Stage.HISTORY_API, "RATE_LIMITED");
    }

    @Test void centerErrorPreservesWireContractAndLogsReturnedCorrelation() throws Exception {
        var response = invoke(new NotificationCenterController(null, null), "failure",
                NotificationApiException.class, new NotificationApiException(404, "NOT_FOUND"));
        assertEquals(404, response.getStatusCode().value());
        assertApiCorrelation(response, Stage.CENTER_API, "NOT_FOUND");
    }

    @Test void pushConflictPreservesSafeEtagAndLogsReturnedCorrelation() throws Exception {
        var response = invoke(new PushInstallationController(null, null), "conflict",
                InstallationAccessPolicy.VersionConflict.class, new InstallationAccessPolicy.VersionConflict(17));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("\"17\"", response.getHeaders().getETag());
        assertApiCorrelation(response, Stage.PUSH_API, "INSTALLATION_CONFLICT");
    }

    @Test void apiErrorsGenerateIndependentCorrelationsAcrossRequestsAndAllowlistKnownCodes() {
        UUID first = apiFailure(Stage.CENTER_API, 400, "INVALID_CURSOR");
        UUID second = apiFailure(Stage.PUSH_API, 403, "FORBIDDEN");
        UUID third = apiFailure(Stage.HISTORY_API, 503, null);
        assertEquals(3, Set.of(first, second, third).size());
        assertEquals(Code.INVALID_CURSOR, fields(appender.list.get(0)).get("code"));
        assertEquals(Code.FORBIDDEN, fields(appender.list.get(1)).get("code"));
        assertEquals(Code.INTERNAL_ERROR, fields(appender.list.get(2)).get("code"));
        assertEquals(second, fields(appender.list.get(1)).get("correlationId"));
    }

    private void assertApiCorrelation(ResponseEntity<NotificationApiErrorDto> response, Stage stage, String code) {
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().code());
        assertNotNull(response.getBody().correlationId());
        assertEquals(1, appender.list.size());
        var logged = fields(appender.list.getFirst());
        assertEquals(stage, logged.get("stage"));
        assertEquals(response.getBody().correlationId(), logged.get("correlationId"));
        assertEquals(code, logged.get("code").toString());
        assertEquals(response.getStatusCode().value(), logged.get("status"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<NotificationApiErrorDto> invoke(Object controller, String method, Class<?> type, Object error)
            throws Exception {
        var handler = controller.getClass().getDeclaredMethod(method, type);
        handler.setAccessible(true);
        return (ResponseEntity<NotificationApiErrorDto>) handler.invoke(controller, error);
    }

    private Map<String, Object> fields(ILoggingEvent entry) {
        var values = new LinkedHashMap<String, Object>();
        assertNotNull(entry.getKeyValuePairs());
        entry.getKeyValuePairs().forEach(pair -> values.put(pair.key, pair.value));
        return values;
    }
}
