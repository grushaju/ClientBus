package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.CrmFailure;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** Cookie scope follows RFC rules. Redirect destinations and response size are bounded. */
final class JdkDikidiTransport implements DikidiTransport {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient client;
    private final int maxBytes;
    JdkDikidiTransport(Duration connectTimeout, int maxBytes) {
        this.maxBytes = maxBytes;
        client = HttpClient.newBuilder().connectTimeout(connectTimeout).cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    JdkDikidiTransport(HttpClient client, int maxBytes) { this.client = client; this.maxBytes = maxBytes; }
    static void destination(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !Set.of("dikidi.ru", "auth.dikidi.ru").contains(uri.getHost()))
            throw new CrmFailure(INVALID_RESPONSE);
    }
    @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                       boolean ajax, String projectId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirect = 0; redirect <= 4; redirect++) {
            destination(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new CrmFailure(TEMPORARY_FAILURE);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", "Mozilla/5.0 ClientBus")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    .header("Accept", ajax ? "application/json, text/javascript, */*; q=0.01" : "text/html");
            if (ajax) {
                builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://dikidi.ru")
                        .header("Referer", uri.getHost().startsWith("auth.") ? "https://dikidi.ru/ru/"
                                : "https://dikidi.ru/ru/owner/journal/?company=" + encode(projectId));
                cookies.getCookieStore().get(uri).stream().filter(c -> "XSRF-TOKEN".equals(c.getName()))
                        .findFirst().ifPresent(c -> builder.header("X-XSRF-TOKEN", URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)));
            }
            if (form == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(form.stream()
                            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                            .collect(java.util.stream.Collectors.joining("&"))));
            CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(builder.build(), info -> new LimitedBody(maxBytes));
            try {
                HttpResponse<byte[]> result = exchange.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                if (Set.of(301,302,303,307,308).contains(result.statusCode())) {
                    String location = result.headers().firstValue("Location").orElseThrow(() -> new CrmFailure(INVALID_RESPONSE));
                    URI next = uri.resolve(location); destination(next);
                    if (form != null && form.stream().anyMatch(e -> e.getKey().equals("password"))
                            && !uri.getHost().equals(next.getHost()) && Set.of(307,308).contains(result.statusCode()))
                        throw new CrmFailure(INVALID_RESPONSE);
                    uri = next;
                    if (result.statusCode() == 303 || (Set.of(301,302).contains(result.statusCode()) && "POST".equals(method))) {
                        method = "GET"; form = null;
                    }
                    continue;
                }
                return new Response(result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
            } catch (InterruptedException error) {
                exchange.cancel(true);
                Thread.currentThread().interrupt(); throw new CrmFailure(TEMPORARY_FAILURE);
            } catch (TimeoutException error) {
                exchange.cancel(true); throw new CrmFailure(TEMPORARY_FAILURE);
            } catch (ExecutionException | RuntimeException error) {
                if (error instanceof CrmFailure failure) throw failure;
                if (error.getCause() instanceof CrmFailure failure) throw failure;
                throw new CrmFailure(TEMPORARY_FAILURE);
            }
        }
        throw new CrmFailure(INVALID_RESPONSE);
    }
    static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    @Override public void close() { cookies.getCookieStore().removeAll(); client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int max;
        private int size;
        private Flow.Subscription subscription;
        LimitedBody(int max) { this.max = max; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                size += buffer.remaining();
                if (size > max) { subscription.cancel(); delegate.onError(new CrmFailure(INVALID_RESPONSE)); return; }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
