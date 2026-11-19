package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmClient;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Зеркало аватара карточки клиента CRM (13.9): в хранилище попадает только файл провайдера, повтор
 * списка его не перекачивает, а всё подозрительное остаётся без аватара — карточка от этого не ломается.
 */
class CrmClientAvatarsTest {
    private static final class Storage implements IAttachmentStorage {
        final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override public StoredAttachmentMetadata store(InputStream content, String fileName, long size, String contentType) {
            var key = "stored/" + objects.size() + "/" + fileName;
            try {
                objects.put(key, content.readAllBytes());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return new StoredAttachmentMetadata(key, fileName, contentType, size);
        }

        @Override public InputStream load(String key) { return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0])); }
        @Override public boolean exists(String key) { return objects.containsKey(key); }
        @Override public void delete(String key) { objects.remove(key); }
    }

    private static CrmClient card(String externalId, String avatar) {
        var fields = new HashSet<>(Set.of("displayName"));
        if (avatar != null) fields.add("avatar");
        return new CrmClient(externalId, "PROJECT", "Клиент " + externalId, Map.of(), Map.of("source", "R08"), fields, avatar);
    }

    @Test void mirrorsProviderFileOnceAndDoesNotDownloadTheSameFileAgain() {
        var storage = new Storage();
        var requested = new ArrayList<String>();
        var avatars = new CrmClientAvatars(storage, url -> {
            requested.add(url);
            return new CrmClientAvatars.Fetch.Image(new byte[] { 1, 2, 3 }, "image/jpeg");
        });
        var canonical = "https://f1.dikidi.ru/c01/v00001/aaaaaaaaaa.jpg";
        var first = avatars.materialize(List.of(card("10", canonical)), Map.of());
        assertEquals(1, first.size());
        var key = first.get("10");
        assertArrayEquals(new byte[] { 1, 2, 3 }, storage.objects.get(key));
        // Тот же файл на другом хосте cdn — тот же файл: сеть не трогается.
        var second = avatars.materialize(List.of(card("10", "https://f2.dikidi.net/c01/v00001/aaaaaaaaaa.jpg")),
                Map.of("10", new CrmClientAvatars.Previous("/c01/v00001/aaaaaaaaaa", key)));
        assertTrue(second.isEmpty());
        assertEquals(List.of(canonical), requested);
    }

    @Test void downloadsAgainWhenFileObjectOrOriginChanged() {
        var storage = new Storage();
        var requested = new ArrayList<String>();
        var avatars = new CrmClientAvatars(storage, url -> {
            requested.add(url);
            return new CrmClientAvatars.Fetch.Image(new byte[] { 9 }, "image/png");
        });
        var next = "https://f1.dikidi.ru/c01/v00002/bbbbbbbbbb.png";
        // Прежнее зеркало потеряно в хранилище — файл качается заново.
        assertFalse(avatars.materialize(List.of(card("11", next)),
                Map.of("11", new CrmClientAvatars.Previous("/c01/v00002/bbbbbbbbbb", "stored/gone.png"))).isEmpty());
        // Другой файл провайдера — прежний ключ не подходит.
        assertEquals(1, avatars.materialize(List.of(card("11", next)),
                Map.of("11", new CrmClientAvatars.Previous("/c01/v00001/aaaaaaaaaa", "stored/old.png"))).size());
        assertEquals(2, requested.size());
    }

    @Test void keepsCardWithoutAvatarForAnythingThatIsNotAProviderImage() {
        var storage = new Storage();
        var requested = new ArrayList<String>();
        var avatars = new CrmClientAvatars(storage, url -> {
            requested.add(url);
            return new CrmClientAvatars.Fetch.Image(new byte[CrmClientAvatars.MAX_BYTES + 1], "image/jpeg");
        });
        var junk = List.of(card("12", "http://f1.dikidi.ru/c01/v00001/xxtt11aaoo.jpg"),
                card("13", "https://example.com/c01/v00001/xxtt11aaoo.jpg"),
                card("14", "https://f1.dikidi.ru/xxtt11aaoo.jpg"),
                card("15", "https://f1.dikidi.ru/c01/v00001/xxtt11aaoo.exe"),
                card("16", "javascript:alert(1)"), card("17", null));
        assertTrue(avatars.materialize(junk, Map.of()).isEmpty());
        assertTrue(requested.isEmpty());
        assertTrue(storage.objects.isEmpty());
        // Файл есть, но это не картинка: в хранилище ничего не попадает.
        var notImage = new CrmClientAvatars(storage, url -> new CrmClientAvatars.Fetch.Image(new byte[] { 1 }, "text/html"));
        assertTrue(notImage.materialize(List.of(card("18", "https://f1.dikidi.ru/c01/v00001/xxtt11aaoo.jpg")), Map.of()).isEmpty());
        // Слишком большой файл тоже не аватар.
        assertTrue(avatars.materialize(List.of(card("19", "https://f1.dikidi.ru/c01/v00001/xxtt11aaoo.jpg")), Map.of()).isEmpty());
        assertTrue(storage.objects.isEmpty());
    }

    @Test void downloadFailureLeavesCardWithoutAvatarInsteadOfBreakingSync() {
        var storage = new Storage();
        var avatars = new CrmClientAvatars(storage, url -> { throw new IllegalStateException("cdn недоступен"); });
        assertTrue(avatars.materialize(List.of(card("20", "https://f1.dikidi.ru/c01/v00001/xxtt11aaoo.jpg")), Map.of()).isEmpty());
        assertTrue(storage.objects.isEmpty());
    }
}
