package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmAvatarUrl;
import kit.penny.clientbus.crm.api.CrmClient;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

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
 * собирает 13.5 — и он обязан считать ссылки по всем {@code avatarurl}, потому что ключи хранилища
 * в хосте разделяются между таблицами.
 */
@Component
public class CrmClientAvatars {
    private static final Logger log = LoggerFactory.getLogger(CrmClientAvatars.class);
    /** Файл аватара — маленькая картинка; всё больше этого подозрительно и не скачивается. */
    static final int MAX_BYTES = 512 * 1024;
    /**
     * Сколько файлов качаем за одну страницу списка (попыток, а не успехов). Страница — 50 строк,
     * аватар есть не у всех; остаток дотягивается на следующих синхронизациях. Без потолка
     * страница с медленным cdn ушла бы за deadline синхронизации и за аренду писателя (120 с).
     */
    static final int MAX_FILES_PER_PAGE = 25;
    /**
     * Вариант файла, который просим у cdn. Список отдаёт аватар ссылкой с `?size=s` (мелкий вариант
     * в css-фоне) и крупный `?size=b` в href обёртки; канонический путь хранится без query — это
     * ключ файла, а не адрес загрузки. Голый путь живым прогоном не подтверждён, `?size=s`
     * подтверждён (анонимный GET: 200, image/jpeg), поэтому качаем вариант провайдера.
     */
    static final String REQUEST_SUFFIX = "?size=s";
    private static final Map<String, String> TYPES = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp");

    /** Что уже лежит в строке crm.client: ключ файла провайдера и локальный ключ зеркала. */
    public record Previous(String origin, String storageKey) { }

    /** Узкий порт сети: проверка зеркала не должна зависеть от настоящего http. */
    interface Fetch {
        record Image(byte[] content, String contentType) { }
        Image get(String url) throws IOException;
    }

    private final IAttachmentStorage storage;
    private final Fetch fetch;
    private final LongSupplier nanoTime;

    public CrmClientAvatars(IAttachmentStorage storage) { this(storage, new Remote()); }

    CrmClientAvatars(IAttachmentStorage storage, Fetch fetch) { this(storage, fetch, System::nanoTime); }

    CrmClientAvatars(IAttachmentStorage storage, Fetch fetch, LongSupplier nanoTime) {
        this.storage = storage; this.fetch = fetch; this.nanoTime = nanoTime;
    }

    /**
     * Локальные ключи для строк, у которых файл аватара появился или сменился. Прежний файл
     * вторично не качается: сравнение идёт по ключу пути провайдера, а не по содержимому.
     *
     * @param previous снимок строк списка до записи (externalId → прежнее зеркало)
     * @return externalId → storageKey; отсутствие строки означает «оставить как было».
     */
    public Map<String, String> materialize(List<CrmClient> items, Map<String, Previous> previous) {
        return materialize(items, previous, MAX_FILES_PER_PAGE, Long.MAX_VALUE);
    }

    /**
     * @param maxFiles    потолок попыток скачивания за вызов
     * @param budgetNanos сколько ещё можно качать, в наносекундах НА ЭТОМ ВЫЗОВЕ. Срок именно
     *                    относительный: абстрактный дедлайн синхронизации считает в её часах, а
     *                    зеркало живёт в своих, и сравнение двух разных часов молча отменило бы
     *                    все скачивания (проверено падающим интеграционным тестом).
     */
    public Map<String, String> materialize(List<CrmClient> items, Map<String, Previous> previous,
                                           int maxFiles, long budgetNanos) {
        var deadline = nanoTime.getAsLong() >= 0 && budgetNanos > Long.MAX_VALUE - nanoTime.getAsLong()
                ? Long.MAX_VALUE : nanoTime.getAsLong() + budgetNanos;
        var mirror = new LinkedHashMap<String, String>();
        var memo = new HashMap<String, String>();
        var attempts = 0;
        for (var item : items) {
            if (attempts >= maxFiles || nanoTime.getAsLong() >= deadline) {
                // Что осталось нескачанным — видно только в отладке и только счётчиками: аватар
                // дотянется на следующем прогоне, но молча терять работу незачем.
                log.debug("Зеркало аватаров остановлено бюджетом: скачано {}, попыток {}", mirror.size(), attempts);
                break;
            }
            if (!item.presentFields().contains("avatar")) continue;
            var canonical = CrmAvatarUrl.canonical(item.avatarUrl());
            // absent (канонической ссылки нет) — не наша забота: писатель сам решит, чистить ли зеркало.
            if (canonical == null) continue;
            var origin = CrmAvatarUrl.key(canonical);
            var known = previous.get(item.externalId());
            // exists() тут — самовосстановление: если объект зеркала исчез (сборщик 13.5), файл
            // качается заново. Для локального диска это stat, для объектного хранилища — HEAD на
            // каждую совпавшую строку; полная синхронизация проекта (~400 клиентов) их делает ~400.
            if (known != null && origin.equals(known.origin()) && known.storageKey() != null
                    && storage.exists(known.storageKey())) continue;
            // Один и тот же файл на двух строках страницы — одно скачивание и один объект.
            var reused = memo.get(origin);
            if (reused != null) { mirror.put(item.externalId(), reused); continue; }
            attempts++;
            var key = mirror(canonical + REQUEST_SUFFIX);
            if (key != null) { memo.put(origin, key); mirror.put(item.externalId(), key); }
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
        } catch (RuntimeException | IOException failure) {
            // Только класс причины: сообщения http-транспорта любят вставлять в текст весь url,
            // а путь файла на cdn — это данные клиента, они в лог не пишутся.
            log.debug("Файл аватара карточки CRM не зеркален: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Анонимный GET: файл лежит на cdn и сессии не требует (замер 18.11.2026). Редиректы не ходим:
     * разрешён только хост из allowlist, а 3xx — это «аватара нет», а не «скачать с чужого хоста».
     * Тело читается ограниченным: размер проверяется до и во время чтения, а не после, иначе ответ
     * в сотни мегабайт лег бы в heap и OutOfMemoryError убил бы поток синхронизации без освобождения
     * аренды писателя. Таймауты короткие: скачивание идёт в цикле синхронизации.
     */
    static final class Remote implements Fetch {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override public Image get(String url) throws IOException {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                                .header("User-Agent", "Mozilla/5.0 ClientBus").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) return null;
                if (declaredLength(response) > MAX_BYTES) return null;
                try (InputStream body = response.body()) {
                    var content = body.readNBytes(MAX_BYTES + 1);
                    // Поместилось ровно MAX_BYTES+1 — значит файл больше лимита: это не аватар.
                    if (content.length > MAX_BYTES) return null;
                    return new Image(content, response.headers().firstValue("content-type").orElse(""));
                }
            } catch (InterruptedException failure) {
                // Прерывание потока синхронизации — не повод его терять: помечаем и выходим отказом.
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        }

        /** Честный content-length, если он есть и вменяем; иначе — 0, пусть решает лимит чтения. */
        private static long declaredLength(HttpResponse<InputStream> response) {
            try {
                return Math.max(0L, response.headers().firstValue("content-length").map(Long::parseLong).orElse(0L));
            } catch (NumberFormatException malformed) {
                return 0L;
            }
        }
    }
}
