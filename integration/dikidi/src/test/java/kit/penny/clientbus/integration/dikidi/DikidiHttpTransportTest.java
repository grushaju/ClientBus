package kit.penny.clientbus.integration.dikidi;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DikidiHttpTransportTest {
    @Test void everyRedirectIsAdmittedSeparatelyAndCooldownStopsBeforeSecondSend(){
        var client=mock(HttpClient.class);var id=UUID.randomUUID();var pauses=DikidiRequests.memory();
        var traffic=new DikidiRequests(DikidiRequests.Settings.defaults(),pauses);
        doReturn(CompletableFuture.completedFuture(redirect(302,"/ru/owner/journal/?company=100"))).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try(var transport=new JdkDikidiTransport(client,1024)){
            var observer=new DikidiTransport.Observer(){
                public void beforeSend(){traffic.acquire(id);}
                public void dispatched(){traffic.dispatched();}
                public void response(DikidiTransport.Response value){pauses.block(id,java.time.Instant.now().plusSeconds(60));}
            };
            assertEquals(DikidiFailure.Code.COOLDOWN,assertThrows(DikidiFailure.class,()->transport.exchange("GET",URI.create("https://dikidi.ru/ru/"),null,false,null,Duration.ofSeconds(2),observer)).code());
            verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));assertEquals(1,traffic.diagnostics().sent());
        }
    }
    @Test void retryAfterSurvivesPhysicalTransport(){
        var client=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);when(response.body()).thenReturn(new byte[0]);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("120")),(a,b)->true));
        doReturn(CompletableFuture.completedFuture(response)).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try(var transport=new JdkDikidiTransport(client,1024)){
            assertEquals("120",transport.exchange("GET",URI.create("https://dikidi.ru/ru/"),null,false,null,Duration.ofSeconds(2)).retryAfter());
        }
    }
    @Test void pauseIsSavedOn429HeadersBeforeReadingItsBody(){
        var client=mock(HttpClient.class);var id=UUID.randomUUID();var traffic=new DikidiRequests(DikidiRequests.Settings.defaults(),DikidiRequests.memory());
        doAnswer(invocation->{
            HttpResponse.BodyHandler<byte[]> handler=invocation.getArgument(1);var info=mock(HttpResponse.ResponseInfo.class);
            when(info.statusCode()).thenReturn(429);when(info.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("120")),(a,b)->true));
            try{handler.apply(info);throw new AssertionError("A 429 body must not be requested");}catch(DikidiFailure error){return CompletableFuture.failedFuture(error);}
        }).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try(var transport=new JdkDikidiTransport(client,1024)){
            assertEquals(DikidiFailure.Code.RATE_LIMITED,assertThrows(DikidiFailure.class,()->transport.exchange("GET",URI.create("https://dikidi.ru/ru/"),null,false,null,Duration.ofSeconds(2),new DikidiTransport.Observer(){
                public void response(DikidiTransport.Response value){traffic.response(id,value);}
            })).code());
            assertNotNull(traffic.retryAt(id));assertEquals(1,traffic.diagnostics().external429());
        }
    }
    @Test void livePolicyRejectsReservationRedirectBeforeSendingIt() {
        for (int status:List.of(301,302,303,307,308)) {
            var client=mock(HttpClient.class); var policy=new DikidiAcceptancePolicy();
            doReturn(CompletableFuture.completedFuture(redirect(status,"/ajax/newrecord/time_reservation/?company_id=100")))
                    .when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
            try (var transport=new JdkDikidiTransport(client,1024,policy)) {
                assertThrows(AssertionError.class,() -> transport.exchange("GET",URI.create("https://dikidi.ru/ru/"),null,false,null,Duration.ofSeconds(2)));
                verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
                assertEquals(1,policy.calls());
            }
        }
    }
    @Test void liveBudgetCountsEachAllowedRedirectAndStopsBeforeExceedingIt() {
        var client=mock(HttpClient.class); var policy=new DikidiAcceptancePolicy(2);
        doReturn(CompletableFuture.completedFuture(redirect(302,"/ru/owner/journal/?company=100")))
                .when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try (var transport=new JdkDikidiTransport(client,1024,policy)) {
            assertThrows(AssertionError.class,() -> transport.exchange("GET",URI.create("https://dikidi.ru/ru/"),null,false,null,Duration.ofSeconds(2)));
            verify(client,times(2)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
            assertEquals(2,policy.calls());
        }
    }
    @Test void redirectedMethodAndRetainedBodyAreCheckedBeforeSending() {
        var client=mock(HttpClient.class); var policy=new DikidiAcceptancePolicy();
        doReturn(CompletableFuture.completedFuture(redirect(307,"/ru/owner/ajax/journal/api/?company=100")))
                .when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try (var transport=new JdkDikidiTransport(client,1024,policy)) {
            assertThrows(AssertionError.class,() -> transport.exchange("POST",URI.create("https://auth.dikidi.ru/ajax/user/auth/"),
                    List.of(Map.entry("unexpected-write","1")),true,null,Duration.ofSeconds(2)));
            verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        }
    }
    @Test void livePolicyAllowsReviewedRedirectAfterPostBecomesGet() {
        var client=mock(HttpClient.class); var policy=new DikidiAcceptancePolicy();
        HttpResponse<byte[]> ok=mock(HttpResponse.class); when(ok.statusCode()).thenReturn(200); when(ok.body()).thenReturn(new byte[0]);when(ok.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        var requests=new ArrayList<HttpRequest>();
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(0,HttpRequest.class));
            return CompletableFuture.completedFuture(requests.size()==1 ? redirect(302,"/ru/owner/journal/?company=100") : ok);
        }).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try (var transport=new JdkDikidiTransport(client,1024,policy)) {
            assertEquals(200,transport.exchange("POST",URI.create("https://dikidi.ru/ru/owner/ajax/journal/api/?company=100"),
                    List.of(Map.entry("request[get][]","company.accesses")),true,"100",Duration.ofSeconds(2)).status());
            assertEquals(2,requests.size()); assertEquals(2,policy.calls());
            assertEquals("GET",requests.getLast().method()); assertEquals(0L,requests.getLast().bodyPublisher().orElseThrow().contentLength());
        }
    }
    @SuppressWarnings("unchecked") private static HttpResponse<byte[]> redirect(int status,String location) {
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Location",List.of(location)),(a,b)->true));
        return response;
    }
    @Test void requestTimeoutCancelsUnderlyingExchange() {
        var client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        doReturn(pending).when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try (var transport = new JdkDikidiTransport(client, 1024)) {
            // Таймаут щедрый (500 мс): микроскопический дедлайн на нагруженном хосте
            // протухал бы до dispatch (законная ветка notDispatched без cancel) — тест
            // о том, что зависший exchange отменяется, а не о гонке планировщика.
            var error = assertThrows(DikidiFailure.class, () -> transport.exchange("GET", URI.create("https://dikidi.ru/ru/"),
                    null, false, null, Duration.ofMillis(500)));
            assertEquals(DikidiFailure.Code.TEMPORARY_FAILURE, error.code());
            assertTrue(pending.isCancelled());
        }
    }
    @Test void oversizedBodyCancelsSubscriptionAndPreservesInvalidResponseCode() {
        var client = mock(HttpClient.class);
        var subscription = mock(Flow.Subscription.class);
        doAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1025])));
            return subscriber.getBody().thenApply(bytes -> (HttpResponse<byte[]>)mock(HttpResponse.class)).toCompletableFuture();
        }).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try (var transport = new JdkDikidiTransport(client, 1024)) {
            assertEquals(DikidiFailure.Code.INVALID_RESPONSE, assertThrows(DikidiFailure.class,
                    () -> transport.exchange("GET", URI.create("https://dikidi.ru/ru/"), null, false, null, Duration.ofSeconds(1))).code());
            verify(subscription).cancel();
        }
    }
    @Test void passwordBodyIsNotForwardedToAnotherOriginByPreservingRedirect() {
        var client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(307);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of("https://dikidi.ru/ru/")), (a,b) -> true));
        doReturn(CompletableFuture.completedFuture(response)).when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try (var transport = new JdkDikidiTransport(client, 1024)) {
            assertEquals(DikidiFailure.Code.INVALID_RESPONSE, assertThrows(DikidiFailure.class, () -> transport.exchange("POST",
                    URI.create("https://auth.dikidi.ru/ajax/user/auth/"), List.of(Map.entry("password","test-secret")),
                    true, null, Duration.ofSeconds(1))).code());
            verify(client, times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        }
    }
    // 13.4: адресность по профилям — CRM не ходит в chat-host, чат не притворяется CRM.
    @Test void chatHostIsAdmittedOnlyForChatProfile() {
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(URI.create("https://chat.dikidi.ru/api/dialogs/list/"), RequestProfile.CRM));
        JdkDikidiTransport.destination(URI.create("https://chat.dikidi.ru/api/dialogs/list/"), RequestProfile.OWNER_CHAT);
        JdkDikidiTransport.destination(URI.create("https://dikidi.ru/ru/owner/chat/"), RequestProfile.OWNER_CHAT);
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(URI.create("https://auth.dikidi.ru/user/logout/"), RequestProfile.OWNER_CHAT));
        JdkDikidiTransport.destination(URI.create("https://auth.dikidi.ru/ajax/user/auth/"), RequestProfile.CRM);
        assertEquals(DikidiFailure.Code.INVALID_RESPONSE, assertThrows(DikidiFailure.class,
                () -> JdkDikidiTransport.destination(URI.create("https://chat.dikidi.ru:8443/api/"), RequestProfile.OWNER_CHAT)).code());
        // Ревю 13.4 (бэклог 13.5): path-гранулярность — OWNER_CHAT не открывает dikidi.ru целиком,
        // а chat-host остаётся доступным только по /api/.
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(URI.create("https://dikidi.ru/ru/owner/journal/?company=100"), RequestProfile.OWNER_CHAT));
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(URI.create("https://dikidi.ru/ru/business/projects"), RequestProfile.OWNER_CHAT));
        JdkDikidiTransport.destination(URI.create("https://dikidi.ru/ru/owner/chat/ajax/page/?company=100"), RequestProfile.OWNER_CHAT);
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(URI.create("https://chat.dikidi.ru/ru/"), RequestProfile.OWNER_CHAT));
        // CRM-профиль широким path не сужается (широкий набор ajax-эндпоинтов — отдельное решение)
        JdkDikidiTransport.destination(URI.create("https://dikidi.ru/ru/owner/journal/?company=100"), RequestProfile.CRM);
    }
    /**
     * Профили файловых hops (15.11.2026). MEDIA ловушка, из-за которой живая загрузка
     * картинок падала бы до сети: путь CDN содержит точку (…jpg), прежний класс
     * [A-Za-z0-9/_-] её не пропускал, фейковые транспорты guard не проходят — тестов не было.
     */
    @Test void mediaAndUploadProfilesAdmitOnlyTheirExactFileHops() {
        JdkDikidiTransport.destination(URI.create("https://f2.dikidi.ru/c51/v50697/23s40uub39.jpg"), RequestProfile.MEDIA);
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(
                URI.create("https://f2.dikidi.ru/upload/?v=2"), RequestProfile.MEDIA));
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(
                URI.create("https://chat.dikidi.ru/c51/v/a.jpg"), RequestProfile.MEDIA));
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(
                URI.create("https://f2.dikidi.ru/c51/../etc/passwd.jpg"), RequestProfile.MEDIA));
        JdkDikidiTransport.destination(URI.create("https://f2.dikidi.ru/upload/"), RequestProfile.UPLOAD);
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(
                URI.create("https://dikidi.ru/upload/"), RequestProfile.UPLOAD));
        assertThrows(DikidiFailure.class, () -> JdkDikidiTransport.destination(
                URI.create("https://f2.dikidi.ru/c51/v/a.jpg"), RequestProfile.UPLOAD));
    }

    @Test void chatProfileRefersToOwnerChatPageWhileCrmKeepsJournalReferer() {
        assertEquals("https://dikidi.ru/ru/owner/chat/?company=100",
                JdkDikidiTransport.referer(RequestProfile.OWNER_CHAT, URI.create("https://chat.dikidi.ru/api/dialogs/list/?company=100"), "100"));
        assertEquals("https://dikidi.ru/ru/owner/journal/?company=100",
                JdkDikidiTransport.referer(RequestProfile.CRM, URI.create("https://dikidi.ru/ru/owner/ajax/journal/api/?company=100"), "100"));
        assertEquals("https://dikidi.ru/ru/",
                JdkDikidiTransport.referer(RequestProfile.CRM, URI.create("https://auth.dikidi.ru/ajax/user/auth/"), null));
    }
    // Ревю 13.4: гонка invalidate (close транспорта) и in-flight запроса — штатный
    // TEMPORARY_FAILURE, а не сырой IllegalStateException за контрактом клиента.
    @Test void closedTransportRefusesAsDikidiFailureNotRawStateError() {
        var transport = new JdkDikidiTransport(HttpClient.newHttpClient(), 1024);
        transport.close();
        var error = assertThrows(DikidiFailure.class, () -> transport.exchange("GET",
                URI.create("https://dikidi.ru/ru/"), null, false, null, Duration.ofSeconds(2)));
        assertEquals(DikidiFailure.Code.TEMPORARY_FAILURE, error.code());
    }
}
