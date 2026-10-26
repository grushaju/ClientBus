package kit.penny.clientbus.server.notification;

import jakarta.validation.Validation;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NotificationContractTest {
    @Test void legacyPlatformMessageOriginFailsClosedAndExplicitLiveSurvivesSerialization() {
        var request=new kit.penny.clientbus.common.dto.message.PlatformMessageRequest(UUID.randomUUID(),"client",null,null,null,
                "sender","external",kit.penny.clientbus.common.enums.MessageType.TEXT,"text",null,Instant.now(),List.of(),EventOrigin.LIVE);
        var live=mapper.readValue(mapper.writeValueAsString(request),request.getClass());
        assertEquals(EventOrigin.LIVE,live.origin());
        @SuppressWarnings("unchecked") var legacy=new HashMap<String,Object>(mapper.convertValue(request,Map.class));
        legacy.remove("origin");
        assertEquals(EventOrigin.SYNC,mapper.convertValue(legacy,request.getClass()).origin());
    }
    @Test void heartbeatRangeMatchesStreamClient() {
        for(int seconds:List.of(1,60)) {
            var properties=new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.heartbeat-seconds",seconds)))
                    .bindOrCreate("clientbus.notifications",NotificationProperties.class);
            assertEquals(seconds,properties.heartbeatSeconds());
        }
        for(int seconds:List.of(0,61,90)) assertThrows(RuntimeException.class,()->
                new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.heartbeat-seconds",seconds)))
                        .bindOrCreate("clientbus.notifications",NotificationProperties.class));
    }
    private final JsonMapper mapper = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> api() throws Exception {
        try (var input = getClass().getResourceAsStream("/notification-contract.openapi.json")) {
            assertNotNull(input);
            return mapper.readValue(input, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    @Test
    void publicRecordsMatchOpenApiProperties() throws Exception {
        Map<String, Object> schemas = map(map(api().get("components")).get("schemas"));
        for (Class<?> type : List.of(NotificationDto.class, NotificationTargetDto.class,
                NotificationPageDto.class, NotificationSummaryDto.class, NotificationReadAllResultDto.class, NotificationClearResultDto.class,
                NotificationPreferencesDto.class, UpdateNotificationPreferencesRequest.class,
                NotificationApiErrorDto.class, UiEventEnvelope.class, UiResourceRef.class, StreamReadyDto.class,
                PushInstallationDto.class, PushInstallationRequest.class, HistorySyncRequest.class,
                HistorySyncResultDto.class, HistorySyncOperationDto.class, HistorySyncOperationPageDto.class)) {
            Set<String> fields = Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
            assertEquals(fields, map(map(schemas.get(type.getSimpleName())).get("properties")).keySet(),
                    type.getSimpleName());
        }
    }

    @Test
    void enumsAndKnownEventsMatchOpenApi() throws Exception {
        Map<String, Object> schemas = map(map(api().get("components")).get("schemas"));
        for (Class<?> type : List.of(NotificationType.class, NotificationTargetKind.class,
                HistoryOperationStatus.class, PushPlatform.class, PushProviderType.class,
                PushEnvironment.class, PushPermission.class)) {
            assertEquals(Arrays.stream(type.getEnumConstants()).map(Object::toString).toList(),
                    map(schemas.get(type.getSimpleName())).get("enum"));
        }
        var values = map(map(map(schemas.get("UiEventEnvelope")).get("properties")).get("type"))
                .get("x-known-values");
        assertEquals(UiEventTypes.ALL, new HashSet<>((List<?>) values));
    }

    @Test
    void referencesResolveAndOperationsRequireBearer() throws Exception {
        Map<String, Object> api = api();
        assertEquals("3.1.1", api.get("openapi"));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), api.get("security"));
        visitReferences(api, api);
        Set<Object> operationIds = new HashSet<>();
        for (Object item : map(api.get("paths")).values()) {
            for (Object value : map(item).values()) {
                assertTrue(operationIds.add(map(value).get("operationId")));
                assertEquals(Set.of("notificationEvents", "startHistorySync").contains(map(value).get("operationId")) ? "feature-flagged" : "implemented",
                        map(value).get("x-implementation-status"));
            }
        }
        assertEquals(14, operationIds.size());
    }

    private void visitReferences(Object value, Map<String, Object> api) {
        if (value instanceof Map<?, ?> object) {
            if (object.get("$ref") instanceof String ref) {
                assertTrue(ref.startsWith("#/"));
                Object target = api;
                for (String part : ref.substring(2).split("/")) target = map(target).get(part);
                assertNotNull(target, ref);
            }
            object.values().forEach(v -> visitReferences(v, api));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> visitReferences(v, api));
        }
    }

    @Test
    void sseRoundTripAllowsUnknownTypeWithoutExposingRouting() throws Exception {
        var event = new UiEventEnvelope(UUID.randomUUID(), 1, "future.event",
                Instant.parse("2026-10-21T10:00:00Z"), null, null, null, null);
        String json = mapper.writeValueAsString(event);
        assertEquals(event, mapper.readValue(json, UiEventEnvelope.class));
        assertFalse(json.contains("organizationId"));
        assertFalse(json.contains("recipientEmployeeIds"));
        assertFalse(json.contains("origin"));
        assertFalse(json.contains("actorEmployeeId"));
    }

    @Test
    void pushSecretsAreWriteOnlyInContractAndRedactedFromToString() throws Exception {
        var request = new PushInstallationRequest(0L, PushPlatform.IOS, PushProviderType.APNS, "app",
                PushEnvironment.SANDBOX, "private-provider-token", "a".repeat(43), true, PushPermission.GRANTED);
        assertFalse(request.toString().contains("private-provider-token"));
        assertFalse(request.toString().contains("a".repeat(43)));
        var attempt = new PushProvider.Request(UUID.randomUUID(), 1, "app", PushEnvironment.SANDBOX,
                PushPlatform.IOS, "private-provider-token", Duration.ofSeconds(1));
        assertFalse(attempt.toString().contains("private-provider-token"));
        assertThrows(IllegalArgumentException.class, () -> new PushProvider.Request(UUID.randomUUID(),
                1, "app", PushEnvironment.SANDBOX, PushPlatform.IOS, "token", Duration.ZERO));
        Map<String, Object> schemas = map(map(api().get("components")).get("schemas"));
        var props = map(map(schemas.get("PushInstallationRequest")).get("properties"));
        assertEquals(true, map(props.get("installationSecret")).get("writeOnly"));
        assertEquals(true, map(props.get("registrationReference")).get("writeOnly"));
        assertFalse(map(map(schemas.get("PushInstallationDto")).get("properties")).containsKey("registrationReference"));
    }

    @Test
    void validationRejectsInvalidPageAndOwnershipProof() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new HistorySyncRequest(null, 50)).isEmpty());
            assertFalse(validator.validate(new HistorySyncRequest(null, 101)).isEmpty());
            assertFalse(validator.validate(new HistorySyncRequest(null, 0)).isEmpty());
            assertFalse(validator.validate(new PushInstallationRequest(0L, PushPlatform.ANDROID, PushProviderType.FCM,
                    "app", PushEnvironment.PRODUCTION, "token", "short", true, PushPermission.GRANTED)).isEmpty());
        }
    }

    @Test
    void occurrenceKeysAreStableScopedAndUnambiguous() {
        UUID account = UUID.randomUUID(), message = UUID.randomUUID();
        assertEquals(NotificationIdentity.incoming(account, "123"), NotificationIdentity.incoming(account, "123"));
        assertNotEquals(NotificationIdentity.incoming(account, "123"), NotificationIdentity.incoming(UUID.randomUUID(), "123"));
        assertNotEquals(NotificationIdentity.digest("ab", "c"), NotificationIdentity.digest("a", "bc"));
        assertNotEquals(NotificationIdentity.outboundFailure(new OutboundAttemptContext(message, UUID.randomUUID())),
                NotificationIdentity.outboundFailure(new OutboundAttemptContext(message, UUID.randomUUID())));
    }

    @Test
    void defaultsAreDisabledAndRetentionBoundsAreValidated() {
        var defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        assertFalse(defaults.creationEnabled());
        assertFalse(defaults.sseEnabled());
        assertFalse(defaults.pushEnabled());
        assertFalse(defaults.historySubmissionEnabled());
        var transportOnly = new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.sse-enabled", "true")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        assertTrue(transportOnly.sseEnabled());
        assertFalse(transportOnly.creationEnabled());
        assertFalse(transportOnly.pushEnabled());
        assertEquals(Duration.ofDays(90), defaults.dedupRetention());
        assertEquals(Duration.ofMinutes(5), defaults.incomingAlertTtl());
        assertThrows(Exception.class, () -> new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.dedup-retention", "P1D")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class));
        assertThrows(Exception.class, () -> new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.lease", "PT5M")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class));
        assertThrows(Exception.class, () -> new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.max-outbox-bytes", "131072")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class));
    }

    @Test void historyRetryWindowCannotBeShorterThanTheClientSevenDayWindow() {
        for (String window : List.of("PT1H", "P1D", "PT167H59M59S")) {
            assertThrows(RuntimeException.class, () -> new Binder(new MapConfigurationPropertySource(Map.of(
                    "clientbus.notifications.history-idempotency-window", window,
                    "clientbus.notifications.history-retention", window)))
                    .bindOrCreate("clientbus.notifications", NotificationProperties.class));
        }
        var longer = new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.history-idempotency-window", "P8D",
                "clientbus.notifications.history-retention", "P9D")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        assertEquals(Duration.ofDays(8), longer.historyIdempotencyWindow());
        assertEquals(Duration.ofDays(9), longer.historyRetention());
    }
}
