package kit.penny.clientbus.integration.dikidi;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.*;

/**
 * JDK-транспорт DIKIDI (13.4, перенесён из crm/dikidi). Cookie scope следует
 * RFC-правилам (ACCEPT_ORIGINAL_SERVER). Назначение каждого hop и размер ответа
 * ограничены профилем запроса: CRM не ходит в chat-host и наоборот; произвольных
 * адресов allow-list не допускает.
 */
public final class JdkDikidiTransport implements DikidiTransport {
    private final CookieManager cookies;
    private final HttpClient client;
    private final int maxBytes;
    @FunctionalInterface public interface RequestPolicy {
        void beforeRequest(String method, URI uri, List<Map.Entry<String, String>> form);
    }
    private final RequestPolicy policy;
    private final DestinationGuard destinationGuard;
    /** Проверка назначения по профилю; тестовый шов подменяет её без ослабления продакшена. */
    @FunctionalInterface public interface DestinationGuard {
        void check(URI uri, RequestProfile profile);
    }
    public JdkDikidiTransport(Duration connectTimeout, int maxBytes) {
        this(connectTimeout, maxBytes, (method, uri, form) -> {});
    }
    public JdkDikidiTransport(Duration connectTimeout, int maxBytes, RequestPolicy policy) {
        this(connectTimeout, maxBytes, policy, JdkDikidiTransport::destination);
    }
    public JdkDikidiTransport(Duration connectTimeout, int maxBytes, RequestPolicy policy, DestinationGuard guard) {
        this(connectTimeout, maxBytes, policy, guard, newJar());
    }
    private JdkDikidiTransport(Duration connectTimeout, int maxBytes, RequestPolicy policy, DestinationGuard guard,
            CookieManager jar) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).cookieHandler(jar)
                .followRedirects(HttpClient.Redirect.NEVER).build(), maxBytes, policy, guard, jar);
    }
    public JdkDikidiTransport(HttpClient client, int maxBytes) { this(client, maxBytes, (method, uri, form) -> {}); }
    public JdkDikidiTransport(HttpClient client, int maxBytes, RequestPolicy policy) {
        this(client, maxBytes, policy, JdkDikidiTransport::destination);
    }
    public JdkDikidiTransport(HttpClient client, int maxBytes, RequestPolicy policy, DestinationGuard guard) {
        this(client, maxBytes, policy, guard, newJar());
    }
    /**
     * Тестовый шов с общей cookie-jar: раньше injected-клиент обходил cookieManager
     * транспорта, и cookie-поток (XSRF через redirect-hop) нельзя было проверить
     * честно; теперь jar общая, поведение совпадает с продакшеном.
     */
    public JdkDikidiTransport(HttpClient client, int maxBytes, RequestPolicy policy, DestinationGuard guard,
            CookieManager jar) {
        this.client = client; this.maxBytes = maxBytes; this.policy = Objects.requireNonNull(policy);
        this.destinationGuard = Objects.requireNonNull(guard); this.cookies = Objects.requireNonNull(jar);
    }
    private static CookieManager newJar() { return new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER); }
    /** Продакшен-правило: https без порта и userInfo, хост — из профиля запроса. */
    public static void destination(URI uri, RequestProfile profile) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !profile.allows(uri.getHost(), uri.getPath()))
            throw new DikidiFailure(INVALID_RESPONSE);
    }
    /** CRM-назначение (совместимость вызовов 13.x). */
    public static void destination(URI uri) { destination(uri, RequestProfile.CRM); }
    static String referer(RequestProfile profile, URI uri, String projectId) {
        if (profile == RequestProfile.OWNER_CHAT || profile == RequestProfile.MEDIA || profile == RequestProfile.UPLOAD)
            return "https://dikidi.ru/ru/owner/chat/?company=" + encode(projectId);
        return uri.getHost().startsWith("auth.") ? "https://dikidi.ru/ru/"
                : uri.getPath().equals("/ru/business/ajax/projects/list/") ? "https://dikidi.ru/ru/business/projects"
                : "https://dikidi.ru/ru/owner/journal/?company=" + encode(projectId);
    }
    @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                       boolean ajax, String projectId, Duration timeout) {
        return exchange(method, uri, form, ajax, projectId, RequestProfile.CRM, timeout, new Observer() {});
    }
    @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                       boolean ajax, String projectId, Duration timeout, Observer observer) {
        return exchange(method, uri, form, ajax, projectId, RequestProfile.CRM, timeout, observer);
    }
    @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
            boolean ajax, String projectId, RequestProfile profile, Duration timeout, Observer observer) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirect = 0; redirect <= 4; redirect++) {
            destinationGuard.check(uri, profile);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", "Mozilla/5.0 ClientBus")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    .header("Accept", ajax ? "application/json, text/javascript, */*; q=0.01" : "text/html");
            if (ajax) {
                builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://dikidi.ru")
                        .header("Referer", referer(profile, uri, projectId));
                cookies.getCookieStore().get(uri).stream().filter(c -> "XSRF-TOKEN".equals(c.getName()))
                        .findFirst().ifPresent(c -> builder.header("X-XSRF-TOKEN", URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)));
            }
            if (form == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(form.stream()
                            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                            .collect(java.util.stream.Collectors.joining("&"))));
            var request = builder.build();
            // Apply to every real send, including redirects after method/body conversion.
            policy.beforeRequest(method, uri, form);
            observer.beforeSend();
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE, null, true);
            request = builder.timeout(Duration.ofNanos(remaining)).build();
            // Once handed to HttpClient absence of a network effect is no longer provable.
            observer.dispatched();
            var limitedResponse = new java.util.concurrent.atomic.AtomicBoolean();
            CompletableFuture<HttpResponse<byte[]>> exchange;
            try {
                // Ревю 13.4: конкурентная инвалидация могла закрыть клиент ровно здесь —
                // штатный отказ транспорта, а не выход сырого ISE за контракт.
                exchange = client.sendAsync(request, info -> {
                    // Persist a pause on headers, even if a 429 body is oversized or never completes.
                    if (info.statusCode() == 429) { limitedResponse.set(true); observer.response(new Response(429, "", info.headers().firstValue("Retry-After").orElse(null))); }
                    return new LimitedBody(maxBytes);
                });
            } catch (RuntimeException error) { throw new DikidiFailure(TEMPORARY_FAILURE); }
            try {
                HttpResponse<byte[]> result = exchange.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                // MEDIA-ответ бинарный: UTF-8-строка из JPEG невозможна, несём байты как есть.
                var response = new Response(result.statusCode(),
                        result.statusCode() >= 300 && result.statusCode() < 400 ? ""
                                : profile == RequestProfile.MEDIA ? "" : new String(result.body(), StandardCharsets.UTF_8),
                        result.headers().firstValue("Retry-After").orElse(null),
                        profile == RequestProfile.MEDIA && result.statusCode() == 200 ? result.body() : null);
                if (!limitedResponse.get()) observer.response(response);
                if (Set.of(301, 302, 303, 307, 308).contains(result.statusCode())) {
                    // A redirect is not confirmation of C06. Replaying its POST could duplicate a message.
                    if (profile == RequestProfile.OWNER_CHAT && "POST".equals(method)
                            && "/api/dialog/message/".equals(uri.getPath()))
                        throw new DikidiFailure(INVALID_RESPONSE);
                    String location = result.headers().firstValue("Location").orElseThrow(() -> new DikidiFailure(INVALID_RESPONSE));
                    URI next = uri.resolve(location); destinationGuard.check(next, profile);
                    if (form != null && form.stream().anyMatch(e -> e.getKey().equals("password"))
                            && !uri.getHost().equals(next.getHost()) && Set.of(307, 308).contains(result.statusCode()))
                        throw new DikidiFailure(INVALID_RESPONSE);
                    uri = next;
                    if (result.statusCode() == 303 || (Set.of(301, 302).contains(result.statusCode()) && "POST".equals(method))) {
                        method = "GET"; form = null;
                    }
                    continue;
                }
                return response;
            } catch (InterruptedException error) {
                exchange.cancel(true);
                Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE);
            } catch (TimeoutException error) {
                exchange.cancel(true); throw new DikidiFailure(TEMPORARY_FAILURE);
            } catch (ExecutionException | RuntimeException error) {
                if (error instanceof DikidiFailure failure) throw failure;
                if (error.getCause() instanceof DikidiFailure failure) throw failure;
                throw new DikidiFailure(TEMPORARY_FAILURE);
            }
        }
        throw new DikidiFailure(INVALID_RESPONSE);
    }
    public static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

    /**
     * multipart/form-data загрузка (C07). Назначение проверяется профилем UPLOAD
     * (только fN.dikidi.ru/upload/); редирект не разыменовывается (загрузка файла
     * не «доворачивается» на чужой хост). Имя поля/filename экранируются как в HTML
     * form (defense-in-depth). Ответ — текст JSON (profile != MEDIA).
     */
    @Override public Response upload(URI uri, String fieldName, String fileName, String contentType,
            byte[] content, String projectId, RequestProfile profile, Duration timeout, Observer observer) {
        destinationGuard.check(uri, profile);
        String boundary = "----ClientBusBoundary" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            String disposition = "Content-Disposition: form-data; name=\"" + sanitize(fieldName)
                    + "\"; filename=\"" + sanitize(fileName) + "\"\r\n"
                    + "Content-Type: " + (contentType == null || contentType.isBlank() ? "application/octet-stream"
                        : contentType.replaceAll("[\\r\\n]", "")) + "\r\n\r\n";
            body.write(("--" + boundary + "\r\n" + disposition).getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException unreachable) { throw new DikidiFailure(TEMPORARY_FAILURE); }
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 ClientBus")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://dikidi.ru")
                .header("Referer", referer(profile, uri, projectId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        cookies.getCookieStore().get(uri).stream().filter(c -> "XSRF-TOKEN".equals(c.getName()))
                .findFirst().ifPresent(c -> builder.header("X-XSRF-TOKEN", URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)));
        policy.beforeRequest("POST", uri, List.of());
        observer.beforeSend();
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        observer.dispatched();
        var limited = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            HttpResponse<byte[]> result = client.sendAsync(request, info -> {
                if (info.statusCode() == 429) { limited.set(true);
                    observer.response(new Response(429, "", info.headers().firstValue("Retry-After").orElse(null))); }
                return new LimitedBody(maxBytes);
            }).get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            var response = new Response(result.statusCode(),
                    result.statusCode() >= 300 && result.statusCode() < 400 ? "" : new String(result.body(), StandardCharsets.UTF_8),
                    result.headers().firstValue("Retry-After").orElse(null));
            if (!limited.get()) observer.response(response);
            return response;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE); }
        catch (TimeoutException | ExecutionException | RuntimeException error) {
            if (error instanceof DikidiFailure failure) throw failure;
            if (error.getCause() instanceof DikidiFailure failure) throw failure;
            throw new DikidiFailure(TEMPORARY_FAILURE);
        }
    }
    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\"\\\\]", "");
    }

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
                if (size > max) { subscription.cancel(); delegate.onError(new DikidiFailure(INVALID_RESPONSE)); return; }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
