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

