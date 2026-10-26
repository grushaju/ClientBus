package kit.penny.clientbus.server.notification.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.UUID;

/**
 * Small, stateless diagnostic boundary for notification pipelines. Only enums and opaque UUIDs
 * are accepted: request bodies, provider endpoints and exception messages must never reach it.
 * Identifiers belong in logs, never in metric tags. No thread-local context crosses async jobs.
 */
public final class NotificationDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDiagnostics.class);

    public enum Stage {
        HISTORY_API, CENTER_API, PUSH_API, UI_RELAY, HISTORY_RELAY, HISTORY_WORKER,
        KAFKA_SUBSCRIBER, UI_GATEWAY, PUSH_WORKER, PUSH_CLEANUP, CENTER_CLEANUP
    }

    public enum Outcome {
        CREATED, ACCEPTED, REJECTED, PUBLISHED, RETRY, QUARANTINED, STALE, STARTED,
        SUCCEEDED, FAILED, STOPPED, DEGRADED, RECOVERED, EXPIRED, CANCELLED
    }

    public enum Code {
        NONE, INVALID_REQUEST, INVALID_CURSOR, IDEMPOTENCY_CONFLICT, INSTALLATION_CONFLICT,
        NOT_FOUND, UNAUTHENTICATED, FORBIDDEN, ACCESS_REVOKED, RATE_LIMITED, UNAVAILABLE,
        INTERNAL_ERROR, DB_UNAVAILABLE, PROVIDER_ACCEPTED, PROVIDER_REJECTED, PROVIDER_TIMEOUT,
        PROVIDER_UNAVAILABLE, REGISTRATION_STALE, LEASE_LOST, QUEUE_FULL, PIPELINE_UNAVAILABLE,
        SHUTDOWN, DEADLINE_EXCEEDED, INVALID_EVENT, INVALID_ENDPOINT, TTL_EXPIRED,
        INVALID_PAYLOAD, INVALID_CONFIGURATION, ALERT_INACTIVE, PROVIDER_RETRY, ENDPOINT_UNAVAILABLE
    }

    private NotificationDiagnostics() {}

    public static void event(Stage stage, Outcome outcome, Code code, UUID eventId,
                             UUID correlationId, UUID operationId) {
        var log = entry(stage, outcome, code);
        id(log, "eventId", eventId);
        id(log, "correlationId", correlationId);
        id(log, "operationId", operationId);
        log.log("notification stage={} outcome={} code={} eventId={} correlationId={} operationId={}",
                stage, outcome, code, eventId, correlationId, operationId);
    }

    public static void push(Stage stage, Outcome outcome, Code code, UUID notificationId, UUID deliveryId) {
        var log = entry(stage, outcome, code);
        id(log, "notificationId", notificationId);
        id(log, "deliveryId", deliveryId);
        log.log("notification stage={} outcome={} code={} notificationId={} deliveryId={}",
                stage, outcome, code, notificationId, deliveryId);
    }

    /** Creates the exact identifier returned by an API error and records its safe counterpart. */
    public static UUID apiFailure(Stage stage, int status, String apiCode) {
        UUID correlationId = UUID.randomUUID();
        Code code = safeApiCode(apiCode);
        var log = entry(stage, Outcome.REJECTED, code);
        log.addKeyValue("status", status);
        log.addKeyValue("correlationId", correlationId);
        log.log("notification stage={} outcome={} code={} status={} correlationId={}",
                stage, Outcome.REJECTED, code, status, correlationId);
        return correlationId;
    }

    private static LoggingEventBuilder entry(Stage stage, Outcome outcome, Code code) {
        return LOG.atInfo().addKeyValue("stage", stage).addKeyValue("outcome", outcome).addKeyValue("code", code);
    }

    private static void id(LoggingEventBuilder log, String key, UUID id) {
        if (id != null) log.addKeyValue(key, id);
    }

    private static Code safeApiCode(String value) {
        if (value == null) return Code.INTERNAL_ERROR;
        return switch (value) {
            case "INVALID_REQUEST" -> Code.INVALID_REQUEST;
            case "INVALID_CURSOR" -> Code.INVALID_CURSOR;
            case "IDEMPOTENCY_CONFLICT" -> Code.IDEMPOTENCY_CONFLICT;
            case "INSTALLATION_CONFLICT" -> Code.INSTALLATION_CONFLICT;
            case "NOT_FOUND" -> Code.NOT_FOUND;
            case "UNAUTHENTICATED" -> Code.UNAUTHENTICATED;
            case "FORBIDDEN" -> Code.FORBIDDEN;
            case "ACCESS_REVOKED" -> Code.ACCESS_REVOKED;
            case "RATE_LIMITED" -> Code.RATE_LIMITED;
            case "UNAVAILABLE" -> Code.UNAVAILABLE;
            default -> Code.INTERNAL_ERROR;
        };
    }
}
