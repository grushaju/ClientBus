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
        // Тот же файл на другом зеркале cdn и в другом варианте размера — тот же файл: сеть не трогается.
        var second = avatars.materialize(List.of(card("10", "https://f2.dikidi.net/c01/v00001/aaaaaaaaaa.jpg?size=b")),
                Map.of("10", new CrmClientAvatars.Previous("/c01/v00001/aaaaaaaaaa", key)));
        assertTrue(second.isEmpty());
        // Качаем вариант, который провайдер реально отдаёт (мелкий из css-фона строки), а не голый путь.
        assertEquals(List.of(canonical + CrmClientAvatars.REQUEST_SUFFIX), requested);
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

    /** Один файл на двух строках страницы — одно скачивание и один объект в хранилище. */
    @Test void sameProviderFileOnTwoRowsIsDownloadedOnce() {
        var requested = new ArrayList<String>();
        var avatars = new CrmClientAvatars(new Storage(), url -> {
            requested.add(url);
            return new CrmClientAvatars.Fetch.Image(new byte[] { 7 }, "image/jpeg");
        });
        var result = avatars.materialize(List.of(card("30", "https://f1.dikidi.ru/c01/v00001/aaaaaaaaaa.jpg?size=s"),
                card("31", "https://f2.dikidi.net/c01/v00001/aaaaaaaaaa.jpg?size=b")), Map.of());
        assertEquals(2, result.size());
        assertEquals(result.get("30"), result.get("31"));
        assertEquals(1, requested.size());
    }

    @Test void downloadFailureLeavesCardWithoutAvatarInsteadOfBreakingSync() {
        var storage = new Storage();
        var avatars = new CrmClientAvatars(storage, url -> { throw new IllegalStateException("cdn недоступен"); });
        assertTrue(avatars.materialize(List.of(card("20", "https://f1.dikidi.ru/c01/v00001/xxtt11aaoo.jpg")), Map.of()).isEmpty());
        assertTrue(storage.objects.isEmpty());
    }

    /** Медленный cdn не уводит синхронизацию за бюджет: и потолок файлов, и срок считаются до скачивания. */
    @Test void networkBudgetStopsDownloadsWithoutBreakingThePage() {
        var rows = List.of(card("21", "https://f1.dikidi.ru/c01/v00001/aaaaaaaaaa.jpg"),
                card("22", "https://f1.dikidi.ru/c01/v00002/bbbbbbbbbb.jpg"));
        // Потолок файлов за страницу.
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var capped = new ArrayList<String>();
        var byCount = new CrmClientAvatars(new Storage(), url -> {
            capped.add(url);
            return new CrmClientAvatars.Fetch.Image(new byte[] { 5 }, "image/jpeg");
        }, clock::get);
        assertEquals(1, byCount.materialize(rows, Map.of(), 1, Long.MAX_VALUE).size());
        assertEquals(1, capped.size());
        // Срок наступает после первого скачивания: второе уже не начинается, страница остаётся валидной.
        var ticking = new java.util.concurrent.atomic.AtomicLong();
        var fetched = new ArrayList<String>();
        var byDeadline = new CrmClientAvatars(new Storage(), url -> {
            fetched.add(url);
            ticking.set(2);
            return new CrmClientAvatars.Fetch.Image(new byte[] { 5 }, "image/jpeg");
        }, ticking::get);
        assertEquals(1, byDeadline.materialize(rows, Map.of(), rows.size(), 1).size());
        assertEquals(1, fetched.size());
        // Просроченный бюджет не начинает ни одного скачивания.
        assertTrue(byDeadline.materialize(rows, Map.of(), rows.size(), 0).isEmpty());
        assertEquals(1, fetched.size());
    }
}
