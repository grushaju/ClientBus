package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmAvatarUrl;
import kit.penny.clientbus.crm.api.CrmClient;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Зеркало аватара карточки клиента CRM (13.9, пункт 2 плана пользователя).
 *
 * <p>DIKIDI отдаёт файл аватара только в css-фоне строки списка клиентов — в форме карточки и на
 * её странице файла нет вовсе (проверено живьём 18.11.2026). Файл доступен без сессии (анонимный
 * GET того же дня: 200, image/jpeg), поэтому его скачивает сервер отдельным запросом и складывает
 * в хранилище вложений. Хосту отдаётся только локальный ключ: сырой url провайдера в avatarUrl
 * доходит до {@code Paths} и роняет /avatar/content (урок 16.11.2026, LocalIAttachmentStorageTest).
 *
 * <p>Аватар — украшение карточки, а не идентификатор: совпадение файла никогда не устанавливает и
 * не подтверждает связь с Client (CRM_SEMANTICS §4, «Аватар: один и тот же файл, но ключом быть не
 * может» в CRM_DIKIDI_PROTOCOL). Скачивание не удерживает транзакцию и не может сорвать
 * синхронизацию: любой сбой оставляет карточку без аватара. Осиротевшие объекты прежних файлов
 * собирает 13.5.
 */
@Component
public class CrmClientAvatars {
    private static final Logger log = LoggerFactory.getLogger(CrmClientAvatars.class);
    /** Файл аватара — маленькая картинка; всё больше этого подозрительно и не скачивается. */
    static final int MAX_BYTES = 512 * 1024;
    /**
     * Вариант файла, который просим у cdn. Список отдаёт аватар ссылкой с `?size=s` (мелкий вариант
     * в css-фоне и крупный `?size=b` в href обёртки), канонический путь хранится без query — это ключ
     * файла, а не адрес загрузки. Голый путь живым прогоном не подтверждён, `?size=s` подтверждён
     * (анонимный GET: 200, image/jpeg), поэтому качаем вариант, который реально отдаёт провайдер.
     */
    static final String REQUEST_SUFFIX = "?size=s";
    private static final Map<String, String> TYPES = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp");

    /** Что уже лежит в строке crm.client: ключ файла провайдера и локальный ключ зеркала. */
    public record Previous(String origin, String storageKey) { }

    /** Узкий порт сети: проверка зеркала не должна зависеть от настоящего http. */
    interface Fetch {
        record Image(byte[] content, String contentType) { }
        Image get(String url) throws Exception;
    }

    private final IAttachmentStorage storage;
    private final Fetch fetch;

    public CrmClientAvatars(IAttachmentStorage storage) { this(storage, new Remote()); }

    CrmClientAvatars(IAttachmentStorage storage, Fetch fetch) { this.storage = storage; this.fetch = fetch; }

    /**
     * Локальные ключи для строк, у которых файл аватара появился или сменился. Прежний файл
     * вторично не качается: сравнение идёт по ключу пути провайдера, а не по содержимому.
     *
     * @param previous снимок строк списка до записи (externalId → прежнее зеркало)
     * @return externalId → storageKey; отсутствие строки означает «оставить как было».
     */
    public Map<String, String> materialize(List<CrmClient> items, Map<String, Previous> previous) {
        var mirror = new LinkedHashMap<String, String>();
        for (var item : items) {
            if (!item.presentFields().contains("avatar")) continue;
            var canonical = CrmAvatarUrl.canonical(item.avatarUrl());
            if (canonical == null) continue;
            var origin = CrmAvatarUrl.key(canonical);
            var known = previous.get(item.externalId());
            if (known != null && origin.equals(known.origin()) && known.storageKey() != null
                    && storage.exists(known.storageKey())) continue;
            var key = mirror(canonical + REQUEST_SUFFIX);
            if (key != null) mirror.put(item.externalId(), key);
        }
        return mirror;
    }

    /** Только изображение с cdn провайдера и разумного размера; любой отказ — пустой аватар. */
    private String mirror(String url) {
        try {
            var image = fetch.get(url);
            if (image == null || image.content() == null) return null;
            var type = image.contentType() == null ? "" : image.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
            var extension = TYPES.get(type);
            if (extension == null || image.content().length == 0 || image.content().length > MAX_BYTES) return null;
            try (var input = new ByteArrayInputStream(image.content())) {
                return storage.store(input, "client-avatar." + extension, image.content().length, type).storageKey();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            log.debug("Файл аватара карточки CRM не зеркален: {}", failure.toString());
            return null;
        }
    }

    /** Анонимный GET: файл лежит на cdn и сессии не требует (замер 18.11.2026). */
    private static final class Remote implements Fetch {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        @Override public Image get(String url) throws Exception {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                            .header("User-Agent", "Mozilla/5.0 ClientBus").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            return new Image(response.body(), response.headers().firstValue("content-type").orElse(""));
        }
    }
}
