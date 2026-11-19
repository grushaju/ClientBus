package kit.penny.clientbus.server.crm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class CrmAvatarHttpTest {
    @Test void headersDoNotEndTheBodyDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/avatar", exchange -> {
            exchange.sendResponseHeaders(200, 1);
            exchange.getResponseBody().flush();
            try { release.await(); exchange.getResponseBody().write(1); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* cancelled body */ }
            finally { exchange.close(); }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var remote = new CrmClientAvatars.Remote(http, Duration.ofMillis(500));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IOException.class,
                    () -> remote.get("http://127.0.0.1:" + server.getAddress().getPort() + "/avatar")));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void oversizedBodyIsCancelledBeforeItCanBeMaterialized() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/avatar", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[CrmClientAvatars.MAX_BYTES + 1]);
            } finally { exchange.close(); }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var remote = new CrmClientAvatars.Remote(http, Duration.ofSeconds(3));
            assertThrows(IOException.class, () -> remote.get("http://127.0.0.1:" + server.getAddress().getPort() + "/avatar"));
        } finally { server.stop(0); }
    }
}
