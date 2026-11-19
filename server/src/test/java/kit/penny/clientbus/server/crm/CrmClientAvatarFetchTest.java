package kit.penny.clientbus.server.crm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящий http-путь зеркала (порт `Remote`): редирект, чужой тип и слишком большое тело не дают
 * аватара, а тело читается ограниченным — ответы больше лимита не ложатся в heap целиком.
 */
class CrmClientAvatarFetchTest {
    private static HttpServer server;
    private static String base;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small", exchange -> {
            var body = new byte[] { 1, 2, 3, 4 };
            exchange.getResponseHeaders().add("content-type", "image/jpeg; charset=binary");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("location", "https://evil.example.test/x.jpg");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/octet", exchange -> {
            var body = "не картинка".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/octet-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/huge", exchange -> {
            // Без content-length (chunked): размер нельзя проверить по заголовку, читатель обязан
            // остановиться сам. Пишем заведомо больше лимита и терпим обрыв — клиент прав.
            exchange.getResponseHeaders().add("content-type", "image/jpeg");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                var chunk = new byte[64 * 1024];
                for (int i = 0; i < 100; i++) out.write(chunk);
            } catch (Exception ignored) {
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void returnsBytesOnlyForAnOkImageResponse() throws Exception {
        var fetch = new CrmClientAvatars.Remote();
        var image = fetch.get(base + "/small?size=s");
        assertNotNull(image);
        assertEquals(4, image.content().length);
        // Тип нормализует уже mirror(): параметры media-type не мешают опознать jpeg.
        assertTrue(image.contentType().startsWith("image/jpeg"));
        assertNull(fetch.get(base + "/moved"), "редирект на чужой хост — это не аватар");
        var foreign = fetch.get(base + "/octet?size=s");
        assertNotNull(foreign);
        assertTrue(new CrmClientAvatars(null, url -> foreign).materialize(
                java.util.List.of(row("https://f1.dikidi.ru/c01/v00001/aaaaaaaaaa.jpg")), java.util.Map.of()).isEmpty(),
                "не-изображение с cdn в хранилище не попадает");
    }

    @Test void oversizedBodyIsRefusedWithoutReadingItIntoMemory() throws Exception {
        // Тело больше лимита без content-length: читатель останавливается на MAX_BYTES+1 и отказывает.
        assertNull(new CrmClientAvatars.Remote().get(base + "/huge?size=s"));
    }

    private static kit.penny.clientbus.crm.api.CrmClient row(String avatar) {
        return new kit.penny.clientbus.crm.api.CrmClient("1", "PROJECT", "Клиент", java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of("displayName", "avatar"), avatar);
    }
}
