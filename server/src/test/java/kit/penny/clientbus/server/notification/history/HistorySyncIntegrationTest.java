package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.outbox.UiOutboxStore;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.security.jwt.JwtService;
import kit.penny.clientbus.server.security.policy.*;
import kit.penny.clientbus.server.service.ChannelAvailabilityService;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=true", "clientbus.notifications.sse-enabled=false",
        "clientbus.notifications.history-submission-enabled=true", "clientbus.notifications.history-consumer-enabled=false",
        "clientbus.notifications.history-relay-poll-ms=3600000", "clientbus.notifications.history-worker-poll-ms=3600000"})
class HistorySyncIntegrationTest extends AbstractIntegrationTest {
    @Autowired HistorySyncService history;
    @Autowired HistoryOperationWorker worker;
    @Autowired HistoryOutboxRelay relay;
    @Autowired UiOutboxStore outbox;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired NotificationProperties config;
    @Autowired HistorySyncSettings limits;
    @Autowired AccessPolicy policy;
    @Autowired ConversationRepository conversations;
    @Autowired ChannelAvailabilityService availability;
    @Autowired IMessageProcessingService messages;
    @Autowired HistorySyncEvents events;
    @Autowired kit.penny.clientbus.server.notification.center.NotificationCenterCleanup cleanup;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired JwtService jwt;
    @Autowired MeterRegistry meters;
    @MockitoBean ChannelConnectorRegistry registry;
    @LocalServerPort int port;
    UUID organization, workspace, conversation, account, owner, other;
    String external, token;
    private <T> T save(T entity) { em.persist(entity); return entity; }
    @BeforeEach void fixtures() {
        jdbc.update("delete from dbo.notificationoutbox");
        jdbc.update("delete from dbo.historysyncoperation");
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org));
            var user = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@history.test", "unused", UserRole.EMPLOYEE));
            var employee = save(new EmployeeEntity(org, user, "History", "Owner", null));
            save(new EmployeeWorkspaceEntity(employee, w));
            var user2 = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@history.test", "unused", UserRole.EMPLOYEE));
            var e2 = save(new EmployeeEntity(org, user2, "History", "Other", null));
            save(new EmployeeWorkspaceEntity(e2, w));
            var ch = save(TestDataFactory.channel(w)); ch.setStatus(ChannelConnectionStatus.CONNECTED);
            var ca = save(TestDataFactory.channelAccount(ch));
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            var c = save(TestDataFactory.conversation(w, ca, client)); c.setAssignedEmployee(employee); em.flush();
            organization = org.getId(); workspace = w.getId(); conversation = c.getId(); account = ca.getId();
            owner = employee.getId(); other = e2.getId(); external = client.getExternalId(); token = jwt.generateToken(user);
        });
    }
    private AccessSubject subject() { return AccessSubject.recipient(owner); }
    private HistorySyncOperationDto submit(String key) { return history.submit(subject(), conversation, key, new HistorySyncRequest(null, 100)); }
    private HistorySyncService.Operation claimed() { return history.claim(submit(UUID.randomUUID().toString()).id()).orElseThrow(); }
    private PlatformMessageRequest message(String externalId) {
        return new PlatformMessageRequest(account, external, null, null, "History contact", external, externalId,
                MessageType.TEXT, "Imported " + externalId, null, Instant.parse("2026-10-01T10:00:00Z"), List.of(), EventOrigin.LIVE);
    }
    private HttpResponse<String> request(String method, String path, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpAcceptanceRecoveryAndStatusDoNotWaitForAConnector() throws Exception {
        String key = UUID.randomUUID().toString();
        var response = request("POST", "/api/conversations/" + conversation + "/history-sync", "{\"beforeExternalId\":null,\"limit\":100}", key);
        assertEquals(202, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var operation = mapper.readValue(response.body(), HistorySyncOperationDto.class);
        assertEquals(HistoryOperationStatus.PENDING, operation.status());
        assertEquals("/api/history-sync-operations/" + operation.id(), response.headers().firstValue("Location").orElseThrow());
        assertEquals(200, request("GET", "/api/history-sync-operations/" + operation.id(), null, null).statusCode());
        assertEquals(200, request("GET", "/api/conversations/" + conversation + "/history-sync-operations", null, null).statusCode());
        assertEquals(200, request("GET", "/api/notifications/summary", null, null).statusCode(), "Bell REST works with SSE disabled");
        verifyNoInteractions(registry);
    }
    @Test void transactionRollbackLeavesNeitherAnOperationNorACommand() {
        double created=meters.counter("clientbus.history.operations.created").count();
        tx.executeWithoutResult(status -> { submit(UUID.randomUUID().toString()); status.setRollbackOnly(); });
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.historysyncoperation", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notificationoutbox", Integer.class));
        assertEquals(created,meters.counter("clientbus.history.operations.created").count());
    }
    @Test void committedMetricsDeduplicateCommandsAndIgnoreRollbackOrLateCompletion() {
        String key=UUID.randomUUID().toString();
        double created=meters.counter("clientbus.history.operations.created").count();
        var op=submit(key);submit(key);
        assertEquals(created+1,meters.counter("clientbus.history.operations.created").count());
        var claim=history.claim(op.id()).orElseThrow();
        var counter=meters.counter("clientbus.history.operations.completed","status","failed","reason","INTERNAL_ERROR");
        double failed=counter.count();
        tx.executeWithoutResult(status->{history.fail(claim,"IMPORT_FAILED");status.setRollbackOnly();});
        assertEquals(failed,counter.count());
        assertEquals(HistoryOperationStatus.RUNNING,history.get(subject(),op.id()).status());
        history.fail(claim,"IMPORT_FAILED");history.fail(claim,"IMPORT_FAILED");
        assertEquals(failed+1,counter.count());
    }
    @Test void brokenDiagnosticsCannotTurnCommittedAcceptanceIntoFailure() {
        var broken=mock(MeterRegistry.class);
        doThrow(new IllegalStateException("unavailable registry")).when(broken).counter("clientbus.history.operations.created");
        var service=new HistorySyncService(named,config,limits,mapper,policy,conversations,availability,messages,events,broken);
        var result=assertDoesNotThrow(()->tx.execute(status->service.submit(subject(),conversation,UUID.randomUUID().toString(),new HistorySyncRequest(null,100))));
        assertEquals(HistoryOperationStatus.PENDING,history.get(subject(),result.id()).status());
    }
    @Test void historyAuthenticationErrorsUseThePrivateJsonContract() throws Exception {
        for (String path : List.of("/api/conversations/" + conversation + "/history-sync",
                "/api/conversations/" + conversation + "/history-sync-operations", "/api/history-sync-operations/" + UUID.randomUUID())) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(mapper.readTree(response.body()).has("code"));
        }
    }
    @Test void concurrentSameKeyIsOneOperationAndDifferentParametersConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 4; i++) calls.add(pool.submit(() -> submit(key).id()));
            UUID first = calls.getFirst().get(10, TimeUnit.SECONDS);
            for (var call : calls) assertEquals(first, call.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.historysyncoperation", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where operationid is not null", Integer.class));
        var conflict = assertThrows(NotificationApiException.class, () -> history.submit(subject(), conversation, key, new HistorySyncRequest("different", 100)));
        assertEquals(409, conflict.status());
    }
    @Test void lost202RecoversTerminalWithSubmissionDisabledAndChannelDisconnected() {
        String key = UUID.randomUUID().toString();
        var accepted = submit(key); var claim = history.claim(accepted.id()).orElseThrow();
        history.importBatch(claim, List.of(message("terminal")), history.execution(claim), true, 1, true);
        jdbc.update("update dbo.channel set status='DISCONNECTED' where channelid=(select channelid from dbo.channelaccount where channelaccountid=?)", account);
        var disabledConfig = new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.history-submission-enabled", "false")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        var disabled = new HistorySyncService(named, disabledConfig, limits, mapper, policy, conversations, availability, messages, events, meters);
        var recovered = tx.execute(status -> disabled.submit(subject(), conversation, key, new HistorySyncRequest("  ", 100)));
        assertEquals(accepted.id(), recovered.id()); assertEquals(HistoryOperationStatus.SUCCEEDED, recovered.status());
        assertTrue(history.list(subject(), conversation, true, 10, null).items().isEmpty());
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.historysyncoperation", Integer.class));
        assertEquals(503, assertThrows(NotificationApiException.class, () -> tx.execute(status -> disabled.submit(subject(), conversation,
                UUID.randomUUID().toString(), new HistorySyncRequest(null, 100)))).status());
    }
    @Test void staleClaimCannotImportMessagesOrMarkTheEndAfterTakeover() {
        var old = claimed(); var execution = history.execution(old);
        jdbc.update("update dbo.historysyncoperation set leaseuntil=clock_timestamp()-interval '1 second' where id=?", old.id());
        var current = history.claim(old.id()).orElseThrow();
        assertTrue(current.version() > old.version());
        assertThrows(HistorySyncService.StaleClaim.class, () -> history.importBatch(old, List.of(message("stale")), execution, true, 1, true));
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Integer.class, conversation));
        history.importBatch(current, List.of(message("current")), execution, true, 1, true);
        history.fail(old, "IMPORT_FAILED");
        assertEquals(HistoryOperationStatus.SUCCEEDED, history.get(subject(), old.id()).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Integer.class, organization));
    }
    @Test void timeoutAndLateResultCannotResurrectOrContinueImport() {
        var claim = claimed(); var execution = history.execution(claim);
        jdbc.update("update dbo.historysyncoperation set deadlineat=createdat+interval '1 microsecond' where id=?", claim.id());
        history.maintain();
        assertEquals("DEADLINE_EXCEEDED", history.get(subject(), claim.id()).errorCode());
        assertThrows(HistorySyncService.StaleClaim.class, () -> history.importBatch(claim, List.of(message("late")), execution, true, 1, true));
        assertFalse(jdbc.queryForObject("select historystartreached from dbo.conversation where conversationid=?", Boolean.class, conversation));
        assertFalse(history.retryQuarantined(jdbc.queryForObject("select id from dbo.notificationoutbox where operationid=?", UUID.class, claim.id())));
    }
    @Test void partialImportRemainsCachedWithoutPersonalEffectsAndFailureStopsLaterBatches() {
        var claim = claimed(); var execution = history.execution(claim);
        history.importBatch(claim, List.of(message("partial")), execution, false, 2, true);
        var wrong = new HistorySyncService.Execution(execution.type(), "wrong-conversation");
        assertThrows(NotificationApiException.class, () -> history.importBatch(claim, List.of(message("wrong")), wrong, true, 2, true));
        history.fail(claim, "IMPORT_FAILED");
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Integer.class, conversation));
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Integer.class, organization));
        assertThrows(HistorySyncService.StaleClaim.class, () -> history.importBatch(claim, List.of(message("after-failure")), execution, true, 2, true));
    }
    @Test void currentAclProtectsStatusDiscoveryRepeatAndExecution() {
        String key = UUID.randomUUID().toString(); var accepted = submit(key);
        assertEquals(404, assertThrows(NotificationApiException.class, () -> history.get(AccessSubject.recipient(other), accepted.id())).status());
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?", other, conversation);
        assertEquals(404, assertThrows(NotificationApiException.class, () -> history.get(subject(), accepted.id())).status());
        assertEquals(404, assertThrows(NotificationApiException.class, () -> history.list(subject(), conversation, true, 10, null)).status());
        assertEquals(404, assertThrows(NotificationApiException.class, () -> submit(key)).status());
        var claim = history.claim(accepted.id()).orElseThrow();
        assertEquals("ACCESS_REVOKED", assertThrows(NotificationApiException.class, () -> history.execution(claim)).code());
    }
    @Test void mixedOutboxSelectorsNeverStealCommandsAndQuarantineIsTerminal() {
        var accepted = submit(UUID.randomUUID().toString());
        var claim = history.claim(accepted.id()).orElseThrow(); // Produces a personal UI invalidation.
        var ui = outbox.claim().orElseThrow(); assertNull(ui.operationId());
        var command = outbox.claimHistory().orElseThrow(); assertEquals(accepted.id(), command.operationId());
        assertTrue(outbox.failed(command, false, "TRANSIENT_PUBLISH"));
        history.fail(claim, "IMPORT_FAILED");
        assertFalse(history.retryQuarantined(command.id()));
        assertTrue(outbox.claimHistory().isEmpty());
    }
    @Test void invalidCommandQuarantinesOnlyItsOperationAndLateManualRetryCannotResurrectIt() {
        var accepted = submit(UUID.randomUUID().toString());
        jdbc.update("update dbo.notificationoutbox set payload=jsonb_set(payload,'{schemaVersion}','99') where operationid=?", accepted.id());
        relay.deliver(outbox.claimHistory().orElseThrow());
        assertEquals("PUBLICATION_FAILED", history.get(subject(), accepted.id()).errorCode());
        UUID id = jdbc.queryForObject("select id from dbo.notificationoutbox where operationid=?", UUID.class, accepted.id());
        assertFalse(history.retryQuarantined(id));
        jdbc.update("update dbo.notificationoutbox set status='RETRY' where id=?", id);
        relay.deliver(outbox.claimHistory().orElseThrow());
        assertEquals(HistoryOperationStatus.FAILED, history.get(subject(), accepted.id()).status());
    }
    @Test void aNewWorkerRecoversPublishedWorkAndExpiredClaimsThroughTheGenericRegistry() {
        var accepted = submit(UUID.randomUUID().toString()); var first = history.claim(accepted.id()).orElseThrow();
        var command = outbox.claimHistory().orElseThrow(); assertTrue(outbox.published(command));
        jdbc.update("update dbo.historysyncoperation set leaseuntil=clock_timestamp()-interval '1 second' where id=?", first.id());
        var connector = mock(IChannelConnector.class);
        when(registry.getConnector(any())).thenReturn(connector);
        when(connector.handle(any(kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand.class)))
                .thenReturn(new SyncConversationHistoryResult(List.of(message("recovered")), true));
        try (var recovered = new WorkerLifetime(new HistoryOperationWorker(history, registry, limits, meters))) {
            recovered.worker.tick();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(HistoryOperationStatus.SUCCEEDED, history.get(subject(), accepted.id()).status()));
        }
        verify(registry).getConnector(ChannelType.TELEGRAM);
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Integer.class, organization));
    }
    record WorkerLifetime(HistoryOperationWorker worker) implements AutoCloseable { public void close() { worker.close(); } }

    @Test void oldPublisherFailureCannotFailANewerPublicationClaim() {
        var accepted = submit(UUID.randomUUID().toString());
        var old = outbox.claimHistory().orElseThrow();
        jdbc.update("update dbo.notificationoutbox set leaseuntil=clock_timestamp()-interval '1 second' where id=?", old.id());
        var current = outbox.claimHistory().orElseThrow();
        history.publicationFailed(old, "PERMANENT_PUBLISH");
        assertEquals(HistoryOperationStatus.PENDING, history.get(subject(), accepted.id()).status());
        assertTrue(outbox.published(current));
    }
    @Test void finalImportAndWatchdogRaceProduceOneConsistentTerminalOutcome() throws Exception {
        var claim = claimed(); var execution = history.execution(claim);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var completion = pool.submit(() -> { start.await();
                try { history.importBatch(claim, List.of(message("race")), execution, true, 1, true); }
                catch (HistorySyncService.StaleClaim expected) { }
                return null;
            });
            var timeout = pool.submit(() -> { start.await();
                jdbc.update("update dbo.historysyncoperation set deadlineat=createdat+interval '1 microsecond' where id=?", claim.id());
                history.maintain(); return null;
            });
            start.countDown(); completion.get(10, TimeUnit.SECONDS); timeout.get(10, TimeUnit.SECONDS);
        }
        // A watchdog pass may SKIP LOCKED while final import still holds the row and then rolls back.
        // Production repeats maintenance; require the same eventual terminal outcome here.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->{
            history.maintain();
            assertTrue(Set.of(HistoryOperationStatus.SUCCEEDED,HistoryOperationStatus.FAILED)
                    .contains(history.get(subject(),claim.id()).status()));
        });
        var result = history.get(subject(), claim.id());
        assertTrue(Set.of(HistoryOperationStatus.SUCCEEDED, HistoryOperationStatus.FAILED).contains(result.status()));
        boolean success = result.status() == HistoryOperationStatus.SUCCEEDED;
        assertEquals(success, jdbc.queryForObject("select historystartreached from dbo.conversation where conversationid=?", Boolean.class, conversation));
        assertEquals(success ? 1 : 0, jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Integer.class, conversation));
        assertEquals(2, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where eventtype='history-operation.invalidated'", Integer.class));
    }
    @Test void watchdogRetriesAnExpiredOperationSkippedByAnEarlierLockedPass() throws Exception {
        var claim=claimed();
        jdbc.update("update dbo.historysyncoperation set deadlineat=createdat+interval '1 microsecond' where id=?",claim.id());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->tx.executeWithoutResult(status->{
                jdbc.queryForObject("select id from dbo.historysyncoperation where id=? for update",UUID.class,claim.id());
                locked.countDown();
                try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }));
            try {
                assertTrue(locked.await(3,TimeUnit.SECONDS));
                history.maintain();
                assertEquals(HistoryOperationStatus.RUNNING,history.get(subject(),claim.id()).status());
            } finally {release.countDown();}
            holder.get(5,TimeUnit.SECONDS);
        }
        history.maintain();
        assertEquals(HistoryOperationStatus.FAILED,history.get(subject(),claim.id()).status());
        assertEquals("DEADLINE_EXCEEDED",history.get(subject(),claim.id()).errorCode());
    }
    @Test void rightsRevokedDuringConnectorCallPreventImportAndTargetRecreation() {
        var claim = claimed(); var execution = history.execution(claim);
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?", other, conversation);
        assertEquals("ACCESS_REVOKED", assertThrows(NotificationApiException.class,
                () -> history.importBatch(claim, List.of(message("revoked")), execution, true, 1, true)).code());
        jdbc.update("delete from dbo.conversation where conversationid=?", conversation);
        assertThrows(NotificationApiException.class, () -> history.importBatch(claim, List.of(message("deleted")), execution, true, 1, true));
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.conversation where conversationid=?", Integer.class, conversation));
    }
    @Test void activeAdmissionLimitNeverBlocksRecoveryOfAnExistingKey() {
        String firstKey = UUID.randomUUID().toString(); var first = submit(firstKey);
        for (int i = 1; i < limits.maxActivePerEmployee(); i++) submit(UUID.randomUUID().toString());
        assertEquals(429, assertThrows(NotificationApiException.class, () -> submit(UUID.randomUUID().toString())).status());
        assertEquals(first.id(), submit(firstKey).id());
    }
    @Test void unavailableGenericConnectorFinishesTheOperationSafely() {
        var accepted = submit(UUID.randomUUID().toString());
        when(registry.getConnector(any())).thenThrow(new IllegalStateException("No implementation"));
        worker.offer(accepted.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("CONNECTOR_UNAVAILABLE", history.get(subject(), accepted.id()).errorCode()));
    }
    @Test void failedPageNeverAdvancesTheCachedCursorPastUnimportedMessages() {
        var accepted = submit(UUID.randomUUID().toString());
        var connector = mock(IChannelConnector.class); when(registry.getConnector(any())).thenReturn(connector);
        var invalid = new PlatformMessageRequest(account, external, null, null, "History contact", external, "invalid-attachment",
                MessageType.TEXT, "Invalid attachment", null, Instant.now(), Collections.singletonList(null), EventOrigin.HISTORY_IMPORT);
        when(connector.handle(any(kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand.class)))
                .thenReturn(new SyncConversationHistoryResult(List.of(message("would-skip"), invalid), true));
        worker.offer(accepted.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("IMPORT_FAILED", history.get(subject(), accepted.id()).errorCode()));
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Integer.class, conversation));
        assertFalse(jdbc.queryForObject("select historystartreached from dbo.conversation where conversationid=?", Boolean.class, conversation));
    }

    @Test void keysetCacheReadKeepsAnOlderPageStableWhenLiveMessagesArrive() throws Exception {
        var claim = claimed(); var execution = history.execution(claim);
        history.importBatch(claim, List.of(message("one"), message("two"), message("three")), execution, true, 3, true);
        assertEquals(3, jdbc.queryForObject("select unreadcount from dbo.conversation where conversationid=?", Integer.class, conversation),
                "Reusing the transaction's conversation lock must not refresh away earlier page updates");
        var ids = jdbc.queryForList("select messageid from dbo.message where conversationid=? order by sentat desc,createdat desc,messageid desc", UUID.class, conversation);
        var page = request("GET", "/api/messages/conversation/" + conversation + "?beforeMessageId=" + ids.getFirst() + "&size=1", null, null);
        assertEquals(200, page.statusCode(), page.body());
        UUID second = UUID.fromString(mapper.readTree(page.body()).get("content").get(0).get("id").asText());
        assertEquals(ids.get(1), second);
        messages.processPlatformMessage(message("new-live"));
        var next = request("GET", "/api/messages/conversation/" + conversation + "?beforeMessageId=" + second + "&size=100", null, null);
        assertEquals(200, next.statusCode(), next.body());
        assertEquals(ids.get(2).toString(), mapper.readTree(next.body()).get("content").get(0).get("id").asText());
    }

    @Test void generalOutboxCleanupPreservesHistoryCommandUntilItsOperationRetentionExpires() {
        var accepted = submit(UUID.randomUUID().toString());
        var command = outbox.claimHistory().orElseThrow();
        assertTrue(outbox.published(command));
        jdbc.update("update dbo.notificationoutbox set publishedat=clock_timestamp()-interval '8 days' where id=?", command.id());
        cleanup.cleanBatch(100);
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where id=?", Integer.class, command.id()));
        assertTrue(history.due().contains(accepted.id()), "Published work remains discoverable while worker slots are busy");
        var claim = history.claim(accepted.id()).orElseThrow();
        history.importBatch(claim, List.of(), history.execution(claim), true, 0, true);
        cleanup.cleanBatch(100);
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where id=?", Integer.class, command.id()));
        assertEquals(accepted.id(), submit(claim.key()).id());

        jdbc.update("""
                update dbo.historysyncoperation set createdat=createdat-interval '9 days',deadlineat=deadlineat-interval '9 days',
                    retainuntil=clock_timestamp()-interval '1 second' where id=?
                """, accepted.id());
        history.maintain();
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where id=?", Integer.class, command.id()));
        history.maintain();
        assertTrue(history.find(accepted.id()).isEmpty());
    }

    @Test void overlappingLiveAndHistoryMessagePersistenceMustNotDeadlockOrLoseTheHistoryPage() throws Exception {
        var claim = claimed(); var execution = history.execution(claim);
        var locked = new CountDownLatch(1); var importPage = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var imported = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("set local statement_timeout='8s'");
                conversations.findByIdForUpdate(conversation).orElseThrow();
                locked.countDown();
                try { assertTrue(importPage.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException stopped) { throw new RuntimeException(stopped); }
                history.importBatch(claim, List.of(message("overlap")), execution, true, 1, true);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var live = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("set local statement_timeout='8s'");
                jdbc.execute("set local application_name='history-live-overlap'");
                messages.processPlatformMessage(message("overlap"));
                em.flush();
            }));
            await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where application_name='history-live-overlap' and wait_event_type='Lock')", Boolean.class)));
            importPage.countDown();
            assertDoesNotThrow(() -> imported.get(10, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> live.get(10, TimeUnit.SECONDS));
            assertEquals(HistoryOperationStatus.SUCCEEDED, history.get(subject(), claim.id()).status());
            assertEquals(1, jdbc.queryForObject("select count(*) from dbo.message where conversationid=? and externalid='overlap'", Integer.class, conversation));
        } finally {
            importPage.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(12, TimeUnit.SECONDS));
        }
    }
}
