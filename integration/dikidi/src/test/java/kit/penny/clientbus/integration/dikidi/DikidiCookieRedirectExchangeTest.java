package kit.penny.clientbus.integration.dikidi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Бэклог ревю 13.4 (закрыт в 13.5): cookie/redirect проверяются живым обменом,
 * а не только замоканным HttpClient — Set-Cookie XSRF-TOKEN переживает redirect-hop
 * и возвращается декодированным в X-XSRF-TOKEN, тело редиректа не отдаётся клиенту.
 */
class DikidiCookieRedirectExchangeTest {
    @Test void xsrfCookieRoundTripsThroughARealRedirectHop() throws IOException {
        var tokens = new ConcurrentLinkedQueue<String>();
        var cookies = new ConcurrentLinkedQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=a%20b; Path=/");
            exchange.getResponseHeaders().add("Location", "/second");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/second", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"));
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var jar = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            var client = HttpClient.newBuilder()
                    .cookieHandler(jar)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            // Шов guard допустим только здесь: localhost-http стенд, продакшен-правило не ослаблен.
            try (var transport = new JdkDikidiTransport(client, 4096, (method, uri, form) -> { }, (uri, profile) -> { }, jar)) {
                var response = transport.exchange("GET",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/first"),
                        null, true, "100", Duration.ofSeconds(10));
                assertEquals(200, response.status());
                assertEquals("ok", response.body(), "тело redirect-промежутка наружу не отдаётся");
            }
            assertEquals("a b", tokens.poll(), "XSRF-TOKEN подставляется в ajax-запрос декодированным");
            assertEquals("XSRF-TOKEN=a%20b", cookies.poll(), "cookie scope держит original server");
        } finally {
            server.stop(0);
        }
    }
}
