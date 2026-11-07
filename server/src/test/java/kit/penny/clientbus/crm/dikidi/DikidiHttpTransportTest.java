package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.CrmFailure;
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
        HttpResponse<byte[]> ok=mock(HttpResponse.class); when(ok.statusCode()).thenReturn(200); when(ok.body()).thenReturn(new byte[0]);
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
            var error = assertThrows(CrmFailure.class, () -> transport.exchange("GET", URI.create("https://dikidi.ru/ru/"),
                    null, false, null, Duration.ofMillis(10)));
            assertEquals(CrmFailure.Code.TEMPORARY_FAILURE, error.code());
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
        }).when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try (var transport = new JdkDikidiTransport(client, 1024)) {
            assertEquals(CrmFailure.Code.INVALID_RESPONSE, assertThrows(CrmFailure.class,
                    () -> transport.exchange("GET", URI.create("https://dikidi.ru/ru/"), null, false, null, Duration.ofSeconds(1))).code());
            verify(subscription).cancel();
        }
    }
    @Test void passwordBodyIsNotForwardedToAnotherOriginByPreservingRedirect() {
        var client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(307);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of("https://dikidi.ru/ru/")), (a,b) -> true));
        doReturn(CompletableFuture.completedFuture(response)).when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try (var transport = new JdkDikidiTransport(client, 1024)) {
            assertEquals(CrmFailure.Code.INVALID_RESPONSE, assertThrows(CrmFailure.class, () -> transport.exchange("POST",
                    URI.create("https://auth.dikidi.ru/ajax/user/auth/"), List.of(Map.entry("password","test-secret")),
                    true, null, Duration.ofSeconds(1))).code());
            verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }
}
