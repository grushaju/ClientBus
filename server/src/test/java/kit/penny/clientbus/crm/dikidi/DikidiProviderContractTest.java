package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

class DikidiProviderContractTest {
    private static final String PROJECT = "100";
    private static final String PUBLIC = "<html><input value='first&amp;csrf' name='telegram_csrf' type='hidden'></html>";
    private static final String ACCOUNT = PUBLIC + "<a href='https://auth.dikidi.ru/user/logout/?csrf=test-only'>Exit</a>";
    private static final String CHECK = "{\"html\":\"<input name='csrf' type='hidden' value='second-csrf'>\"}";
    private static final String AUTH = "{\"callback\":[\"sw.auth.complete();\"]}";
    private static final String CATALOG = """
            {"company":{"accesses":{"100":{"actor":{"id":"actor"}}}},
             "masters":{"info":{"ids":["m1","m2"],"data":{
                "m1":{"id":"m1","item_type":"master","is_master":"1","title":"Master one"},
                "m2":{"id":"m2","item_type":"master","is_master":"1","title":"Master two"}
             }}}}
            """;
    private record Request(String method, URI uri, List<Map.Entry<String,String>> form, String project) {}
    private static final class Fake implements DikidiTransport {
        final List<Request> calls = new CopyOnWriteArrayList<>();
        final Deque<Response> responses = new ConcurrentLinkedDeque<>();
        void add(String... bodies) { for (String body : bodies) responses.add(new Response(200, body)); }
        public Response exchange(String method, URI uri, List<Map.Entry<String,String>> form, boolean ajax, String project, Duration timeout) {
            calls.add(new Request(method, uri, form == null ? null : List.copyOf(form), project));
            assertTrue(timeout.compareTo(Duration.ofSeconds(15)) <= 0);
            Response response = responses.poll();
            assertNotNull(response, "Unexpected HTTP call to " + uri.getPath()); return response;
        }
    }
    private final Fake fake = new Fake();
    private DikidiProvider provider;
    @BeforeEach void setup() { provider = provider(() -> fake); }
    @AfterEach void close() { provider.close(); }
    private static DikidiProvider provider(java.util.function.Supplier<DikidiTransport> factory) {
        return new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 4096), factory);
    }
    private static CrmAccess access(UUID id, long version) { return new CrmAccess(id, version, "70000000000", "test-only-secret".toCharArray()); }
    private static String field(Request request, String key) {
        return request.form().stream().filter(e -> e.getKey().equals(key)).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private void loginThen(String... bodies) { fake.add(PUBLIC, CHECK, AUTH); fake.add(bodies); }

    private static String projectPage(String html) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("append",".project-list","html",html));
    }
    @Test void listsAccountProjectsAcrossPagesWithoutSelectingOrChangingAProject() {
        loginThen(DikidiProjectsHtmlTest.BUSINESS,projectPage(DikidiProjectsHtmlTest.row("100","First")+DikidiProjectsHtmlTest.row("200","Second")),projectPage(DikidiProjectsHtmlTest.EMPTY));
        try (var access=access(UUID.randomUUID(),1)) {
            assertEquals(List.of("100","200"),provider.listProjects(access).stream().map(CrmProjectInfo::externalId).toList());
        }
        assertEquals("/ru/business/projects",fake.calls.get(3).uri().getPath());
        assertEquals("0",field(fake.calls.get(4),"offset")); assertEquals("2",field(fake.calls.get(5),"offset"));
        assertTrue(fake.calls.subList(3,fake.calls.size()).stream().allMatch(call -> call.project()==null));
    }
    @Test void emptyProjectListIsSuccessfulButUnknownOrRepeatedPagesAreRejected() {
        loginThen(DikidiProjectsHtmlTest.BUSINESS,projectPage(DikidiProjectsHtmlTest.EMPTY));
        try (var access=access(UUID.randomUUID(),1)) { assertTrue(provider.listProjects(access).isEmpty()); }
        loginThen(DikidiProjectsHtmlTest.BUSINESS,projectPage(DikidiProjectsHtmlTest.row("100","A")),projectPage(DikidiProjectsHtmlTest.row("100","A")));
        try (var access=access(UUID.randomUUID(),1)) { assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> provider.listProjects(access)).code()); }
        loginThen(DikidiProjectsHtmlTest.BUSINESS,"{\"append\":\".other\",\"html\":\"\"}");
        try (var access=access(UUID.randomUUID(),1)) { assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> provider.listProjects(access)).code()); }
    }
    @Test void blankProjectHtmlIsTerminalOnlyAfterANonemptyVerifiedPage() {
        loginThen(DikidiProjectsHtmlTest.BUSINESS,projectPage(DikidiProjectsHtmlTest.row("100","A")),projectPage("\n\t   "));
        try (var access=access(UUID.randomUUID(),1)) { assertEquals(1,provider.listProjects(access).size()); }
        loginThen(DikidiProjectsHtmlTest.BUSINESS,projectPage("\n\t   "));
        try (var access=access(UUID.randomUUID(),1)) { assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> provider.listProjects(access)).code()); }
    }
    @Test void projectListRecoversAnExpiredSessionButDoesNotTreatAnUnknownPageAsEmpty() {
        loginThen(PUBLIC,PUBLIC,CHECK,AUTH,DikidiProjectsHtmlTest.BUSINESS,projectPage(DikidiProjectsHtmlTest.EMPTY));
        try (var access=access(UUID.randomUUID(),1)) { assertTrue(provider.listProjects(access).isEmpty()); }
        assertEquals(2,passwordLogins());
        loginThen("<html>Unexpected</html>");
        try (var access=access(UUID.randomUUID(),1)) { assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> provider.listProjects(access)).code()); }
    }

    @Test void verificationReadsCurrentSessionWithoutAnotherPasswordLogin() {
        loginThen("journal", CATALOG, ACCOUNT, ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT);
            provider.verifyConnection(access);
            provider.verifyConnection(access);
        }
        assertEquals(1, passwordLogins());
        assertEquals(7, fake.calls.size());
    }
    @Test void verificationRecoversExpiredSessionOnce() {
        loginThen("journal", CATALOG);
        fake.responses.add(new DikidiTransport.Response(401, "expired"));
        fake.add(PUBLIC, CHECK, AUTH, ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT);
            provider.verifyConnection(access);
        }
        assertEquals(2, passwordLogins());
        assertTrue(fake.responses.isEmpty());
    }
    @Test void verificationStopsAfterRejectedRecoveryLogin() {
        loginThen("journal", CATALOG);
        fake.responses.add(new DikidiTransport.Response(401, "expired"));
        fake.add(PUBLIC, CHECK, "{}");
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT);
            assertEquals(AUTH_REQUIRED, assertThrows(CrmFailure.class, () -> provider.verifyConnection(access)).code());
        }
        assertEquals(2, passwordLogins());
        assertTrue(fake.responses.isEmpty());
    }
    @Test void repeatedExpiryInvalidatesCachedAuthenticationAndStopsRecovery() {
        loginThen("journal", CATALOG);
        fake.responses.add(new DikidiTransport.Response(401, "expired"));
        fake.add(PUBLIC, CHECK, AUTH);
        fake.responses.add(new DikidiTransport.Response(401, "expired again"));
        fake.add(PUBLIC, CHECK, AUTH, ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT);
            assertEquals(AUTH_REQUIRED, assertThrows(CrmFailure.class, () -> provider.verifyConnection(access)).code());
            assertEquals(2, passwordLogins());
            provider.verifyConnection(access);
        }
        assertEquals(3, passwordLogins());
        assertTrue(fake.responses.isEmpty());
    }
    @Test void verificationPreservesSessionOnNonAuthenticationFailures() {
        for (int status : List.of(403, 429, 503)) {
            fake.calls.clear(); fake.responses.clear();
            loginThen("journal", CATALOG);
            fake.responses.add(new DikidiTransport.Response(status, "unavailable"));
            fake.add(ACCOUNT);
            try (var access = access(UUID.randomUUID(), 1)) {
                provider.readMasters(access, PROJECT);
                assertEquals(status == 403 ? ACCESS_DENIED : status == 429 ? RATE_LIMITED : TEMPORARY_FAILURE,
                        assertThrows(CrmFailure.class, () -> provider.verifyConnection(access)).code());
                provider.verifyConnection(access);
                assertEquals(1, passwordLogins());
            }
        }
    }
    @Test void initialVerificationDoesNotRetryRejectedCredentials() {
        fake.add(PUBLIC, CHECK, "{}");
        try (var access = access(UUID.randomUUID(), 1)) {
            assertEquals(AUTH_REQUIRED, assertThrows(CrmFailure.class, () -> provider.verifyConnection(access)).code());
        }
        assertEquals(1, passwordLogins());
    }
    @Test void passwordEndpointHttp400StopsBeforeSessionProbeAndDoesNotRetryLogin() {
        fake.add(PUBLIC,CHECK);
        fake.responses.add(new DikidiTransport.Response(400,"{\"error\":{\"code\":\"INVALID_PASSWORD\",\"message\":\"Invalid password test-only-secret\"}}"));
        try (var access=access(UUID.randomUUID(),1)) {
            var error=assertThrows(CrmFailure.class,() -> provider.verifyConnection(access));
            assertEquals(AUTH_REQUIRED,error.code()); assertNull(error.getCause());
            assertFalse(error.toString().contains("test-only-secret"));
        }
        assertEquals(1,passwordLogins()); assertEquals(3,fake.calls.size()); assertTrue(fake.responses.isEmpty());
    }
    @Test void unboundAccountVerificationReusesCurrentSession() {
        loginThen(ACCOUNT, ACCOUNT, ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.verifyConnection(access);
            provider.verifyConnection(access);
            provider.verifyConnection(access);
        }
        assertEquals(1, passwordLogins());
        assertTrue(fake.responses.isEmpty());
    }
    @Test void verificationDoesNotDependOnLastProjectOrUseItsAccess() {
        loginThen("journal", CATALOG, "other journal", CATALOG.replace("\"100\"", "\"200\""), ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT);
            provider.readMasters(access, "200");
            provider.verifyConnection(access);
        }
        Request check = fake.calls.getLast();
        assertEquals("/ru/", check.uri().getPath());
        assertNull(check.uri().getQuery());
        assertNull(check.project());
        assertEquals(1, passwordLogins());
    }
    @Test void publicHttp200DoesNotProveAuthenticationAndTriggersOneRecovery() {
        loginThen(ACCOUNT, PUBLIC, PUBLIC, CHECK, AUTH, ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.verifyConnection(access);
            provider.verifyConnection(access);
        }
        assertEquals(2, passwordLogins());
        assertTrue(fake.responses.isEmpty());
    }
    @Test void malformedPageIsInvalidResponseAndPreservesSession() {
        loginThen(ACCOUNT, "<html>upstream error</html>", ACCOUNT);
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.verifyConnection(access);
            assertEquals(INVALID_RESPONSE, assertThrows(CrmFailure.class, () -> provider.verifyConnection(access)).code());
            provider.verifyConnection(access);
        }
        assertEquals(1, passwordLogins());
    }
    @Test void authenticationEvidenceMustBeAnExactLogoutAnchor() {
        assertTrue(DikidiHtml.parse(ACCOUNT).authenticatedAccount());
        for (String html : List.of(PUBLIC,
                "<!-- <a href='https://auth.dikidi.ru/user/logout/'> -->",
                "<script>const x=\"<a href='https://auth.dikidi.ru/user/logout/'>\";</script>",
                "<a data-href='https://auth.dikidi.ru/user/logout/'>Exit</a>",
                "<a href='https://evil.example/user/logout/'>Exit</a>",
                "<a href='https://auth.dikidi.ru.evil.example/user/logout/'>Exit</a>",
                "<a href='https://evil@auth.dikidi.ru/user/logout/'>Exit</a>",
                "<a href='http://auth.dikidi.ru/user/logout/'>Exit</a>",
                "<a href='https://auth.dikidi.ru/user/logout/fake'>Exit</a>"))
            assertFalse(DikidiHtml.parse(html).authenticatedAccount());
    }
    private long passwordLogins() {
        return fake.calls.stream().filter(call -> call.uri().getPath().equals("/ajax/user/auth/")).count();
    }

    @Test void csrfStepsBootstrapAndAllMasterRowsArePreserved() {
        loginThen("journal", CATALOG);
        try (var access = access(UUID.randomUUID(), 1)) {
            var result = provider.readMasters(access, PROJECT);
            assertEquals(List.of("m1","m2"), result.items().stream().map(CrmMaster::externalId).toList());
            assertEquals(CrmReadResult.Completeness.COMPLETE, result.completeness());
            assertEquals("100:MASTERS", result.scope());
        }
        assertEquals(List.of("/ru/","/ajax/check/auth/","/ajax/user/auth/","/ru/owner/journal/","/ru/owner/ajax/journal/api/"),
                fake.calls.stream().map(c -> c.uri().getPath()).toList());
        assertEquals("first&csrf", field(fake.calls.get(1), "telegram_csrf"));
        assertEquals("second-csrf", field(fake.calls.get(2), "csrf"));
        assertEquals("1", field(fake.calls.get(2), "pdAgreement"));
        assertEquals(2, fake.calls.get(4).form().stream().filter(e -> e.getKey().equals("request[get][]")).count());
        assertEquals(PROJECT, fake.calls.get(4).project());
        assertTrue(fake.calls.stream().noneMatch(c -> c.uri().getPath().matches(".*(reservation|save|remove|cancel).*")));
    }
    @Test void successErrorObjectIsNotTreatedAsFailureAndTimezoneIsNotGuessed() {
        loginThen("journal", CATALOG, "{\"html\":\"<h2>Project &amp; title</h2>\"}",
                "{\"error\":{\"code\":0,\"message\":null},\"data\":{\"currency\":{\"iso\":\"RUB\"}}}");
        try (var access = access(UUID.randomUUID(), 1)) {
            var info = provider.inspectProject(access, PROJECT);
            assertEquals(PROJECT, info.externalId()); assertEquals("Project & title", info.displayName());
            assertEquals("RUB", info.currency()); assertNull(info.zoneId());
        }
    }
    @Test void anExplicitEmptyMasterCollectionCanBeComplete() {
        loginThen("journal", CATALOG.replace("\"ids\":[\"m1\",\"m2\"]", "\"ids\":[]")
                .replaceAll("(?s)\"data\":\\{.*", "\"data\":[]}}}"));
        try (var access = access(UUID.randomUUID(), 1)) {
            var result = provider.readMasters(access, PROJECT);
            assertTrue(result.items().isEmpty()); assertEquals(CrmReadResult.Completeness.COMPLETE, result.completeness());
        }
    }
    @Test void differentConnectionAndCredentialVersionsHaveDifferentSessions() {
        var transports = new CopyOnWriteArrayList<Fake>();
        provider.close(); provider = provider(() -> {
            var transport = new Fake(); transport.add(PUBLIC, CHECK, AUTH, "journal", CATALOG);
            transports.add(transport); return transport;
        });
        UUID connection = UUID.randomUUID();
        try (var first = access(connection, 1); var second = access(UUID.randomUUID(), 1); var changed = access(connection, 2)) {
            provider.readMasters(first, PROJECT); provider.readMasters(second, PROJECT); provider.readMasters(changed, PROJECT);
        }
        assertEquals(3, transports.size());
        assertTrue(transports.stream().allMatch(t -> t.calls.stream().filter(c -> c.uri().getPath().equals("/ajax/user/auth/")).count() == 1));
    }
    @Test void sameSessionInitializesEveryRequestedProject() {
        loginThen("journal", CATALOG, "other journal", CATALOG.replace("\"100\"", "\"200\""));
        try (var access = access(UUID.randomUUID(), 1)) {
            provider.readMasters(access, PROJECT); provider.readMasters(access, "200");
        }
        assertEquals(1, fake.calls.stream().filter(c -> c.uri().getPath().equals("/ajax/user/auth/")).count());
        assertEquals(List.of("company=100","company=200"), fake.calls.stream().filter(c -> c.uri().getPath().equals("/ru/owner/journal/")).map(c -> c.uri().getQuery()).toList());
    }
    @Test void wrongProjectAndNullProjectAccessFailClosed() {
        for (String response : List.of(CATALOG.replace("\"100\"", "\"200\""),
                CATALOG.replace("{\"actor\":{\"id\":\"actor\"}}", "null"))) {
            var transport = new Fake(); transport.add(PUBLIC, CHECK, AUTH, "journal", response);
            try (var isolated = provider(() -> transport); var access = access(UUID.randomUUID(), 1)) {
                assertEquals(CONTEXT_MISMATCH, assertThrows(CrmFailure.class, () -> isolated.readMasters(access, PROJECT)).code());
            }
        }
    }
    @Test void missingRowsAndConflictingIdsAreNotSuccessfulEmptyResults() {
        for (String response : List.of(CATALOG.replace("\"ids\":[\"m1\",\"m2\"]", "\"ids\":[\"m1\"]"),
                CATALOG.replace("\"m2\":{\"id\":\"m2\"", "\"m2\":{\"id\":\"other\""))) {
            var transport = new Fake(); transport.add(PUBLIC, CHECK, AUTH, "journal", response);
            try (var isolated = provider(() -> transport); var access = access(UUID.randomUUID(), 1)) {
                assertThrows(CrmFailure.class, () -> isolated.readMasters(access, PROJECT));
            }
        }
    }
    @Test void businessErrorAtHttp200IsDeniedAndDoesNotTriggerAnAuthLoop() {
        loginThen("journal", "{\"error\":1,\"message\":\"denied test-only-secret\"}");
        try (var access = access(UUID.randomUUID(), 1)) {
            CrmFailure error = assertThrows(CrmFailure.class, () -> provider.readMasters(access, PROJECT));
            assertEquals(ACCESS_DENIED, error.code()); assertFalse(error.toString().contains("test-only-secret"));
            assertNull(error.getCause()); assertEquals(5, fake.calls.size());
        }
    }
    @Test void expiredSessionRetriesSafeReadOnlyOnce() {
        fake.add(PUBLIC, CHECK, AUTH);
        fake.responses.add(new DikidiTransport.Response(401, "expired"));
        fake.add(PUBLIC, CHECK, AUTH, "journal", CATALOG);
        try (var access = access(UUID.randomUUID(), 1)) { assertEquals(2, provider.readMasters(access, PROJECT).items().size()); }
        assertEquals(2, fake.calls.stream().filter(c -> c.uri().getPath().equals("/ajax/user/auth/")).count());
    }
    @Test void repeatedLoginHtmlStopsAfterOneRecovery() {
        fake.add(PUBLIC, CHECK, AUTH, "journal", "<input name='password'>",
                PUBLIC, CHECK, AUTH, "journal", "<input name='password'>");
        try (var access = access(UUID.randomUUID(), 1)) {
            assertEquals(AUTH_REQUIRED, assertThrows(CrmFailure.class, () -> provider.readMasters(access, PROJECT)).code());
        }
        assertEquals(2, fake.calls.stream().filter(c -> c.uri().getPath().equals("/ajax/user/auth/")).count());
    }
    @Test void oversizedResponseAndRateLimitAreTypedFailures() {
        loginThen("journal", "x".repeat(4097));
        try (var access = access(UUID.randomUUID(), 1)) {
            assertEquals(INVALID_RESPONSE, assertThrows(CrmFailure.class, () -> provider.readMasters(access, PROJECT)).code());
        }
        var limited = new Fake(); limited.responses.add(new DikidiTransport.Response(429, "provider body"));
        try (var isolated = provider(() -> limited); var access = access(UUID.randomUUID(), 1)) {
            assertEquals(RATE_LIMITED, assertThrows(CrmFailure.class, () -> isolated.readMasters(access, PROJECT)).code());
        }
    }
    @Test void invalidDestinationsAndProjectParametersNeverReachTransport() {
        for (String uri : List.of("http://dikidi.ru/", "https://example.org/", "https://user:pass@dikidi.ru/", "https://dikidi.ru:8443/"))
            assertEquals(INVALID_RESPONSE, assertThrows(CrmFailure.class, () -> JdkDikidiTransport.destination(URI.create(uri))).code());
        try (var access = access(UUID.randomUUID(), 1)) {
            assertEquals(CONTEXT_MISMATCH, assertThrows(CrmFailure.class, () -> provider.readMasters(access, "1&company=2")).code());
        }
        assertTrue(fake.calls.isEmpty());
    }
    @Test void localHttpCapacityIsBusyWhileAnUpstream429RemainsRateLimited() throws Exception {
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(4);
        try (var isolated=provider(() -> (method,uri,form,ajax,project,timeout) -> {
            String body;
            if (uri.getPath().equals("/ru/")) {
                entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                body=ACCOUNT;
            } else if (uri.getPath().equals("/ajax/check/auth/")) body=CHECK;
            else body=AUTH;
            return new DikidiTransport.Response(200,body);
        })) {
            var pending=new ArrayList<Future<?>>();
            try {
                for (int index=0;index<4;index++) pending.add(workers.submit(() -> {
                    try (var access=access(UUID.randomUUID(),1)) { isolated.verifyConnection(access); }
                }));
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                try (var access=access(UUID.randomUUID(),1)) {
                    assertEquals(CONNECTION_BUSY,assertThrows(CrmFailure.class,() -> isolated.verifyConnection(access)).code());
                }
            } finally { release.countDown(); for (var task : pending) task.get(5,TimeUnit.SECONDS); }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
        var limited=new Fake(); limited.responses.add(new DikidiTransport.Response(429,"limited"));
        try (var isolated=provider(() -> limited); var access=access(UUID.randomUUID(),1)) {
            assertEquals(RATE_LIMITED,assertThrows(CrmFailure.class,() -> isolated.verifyConnection(access)).code());
        }
    }
    @Test void parallelVerificationCoalescesOneLogin() throws Exception {
        CountDownLatch publicEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        provider.close(); provider = provider(() -> new DikidiTransport() {
            public Response exchange(String method, URI uri, List<Map.Entry<String,String>> form, boolean ajax, String project, Duration timeout) {
                if (uri.getPath().equals("/ru/")) {
                    publicEntered.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                    return new Response(200, ACCOUNT);
                }
                if (uri.getPath().equals("/ajax/check/auth/")) return new Response(200, CHECK);
                count.incrementAndGet(); return new Response(200, AUTH);
            }
        });
        UUID id = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var first = access(id, 1); var second = access(id, 1)) {
            Future<?> initial = executor.submit(() -> provider.verifyConnection(first));
            assertTrue(publicEntered.await(1, TimeUnit.SECONDS));
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread follower = new Thread(() -> { try { provider.verifyConnection(second); } catch (Throwable e) { failure.set(e); } });
            follower.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (follower.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(Thread.State.TIMED_WAITING, follower.getState());
            release.countDown(); initial.get(2, TimeUnit.SECONDS); follower.join(2000);
            assertFalse(follower.isAlive()); assertNull(failure.get()); assertEquals(1, count.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
