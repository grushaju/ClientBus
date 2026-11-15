package kit.penny.clientbus.integration.dikidi;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Один физический HTTP-обмен с DIKIDI (13.4, перенесено из crm/dikidi без смены
 * механики). Базовый 6-аргументный метод — функциональное ядро (тем же составом,
 * что и до 13.4): реализация, получив Observer, обязана вызывать
 * beforeSend/dispatched/response ровно в этом порядке — evidence отправки
 * строится на этих колбэках. Профиль запроса (13.4) добавлен расширяющим
 * overload-ом: профили-невосприимчивые фейки продолжают реализовывать базовый
 * метод, JDK-реализация переопределяет профильную версию и проверяет назначение
 * по профилю.
 */
public interface DikidiTransport extends AutoCloseable {
    record Response(int status, String body, String retryAfter, byte[] binary) {
        public Response(int status, String body) { this(status, body, null, null); }
        public Response(int status, String body, String retryAfter) { this(status, body, retryAfter, null); }
        @Override public String toString() { return "DikidiResponse[status=" + status + "]"; }
    }
    Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                      boolean ajax, String projectId, Duration timeout);
    default Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                              boolean ajax, String projectId, Duration timeout, Observer observer) {
        observer.beforeSend(); observer.dispatched();
        var response = exchange(method, uri, form, ajax, projectId, timeout);
        observer.response(response); return response;
    }
    /** Назначение и заголовки зависят от профиля. Фейки могут профиль игнорировать. */
    default Response exchange(String method, URI uri, List<Map.Entry<String, String>> form, boolean ajax,
                              String projectId, RequestProfile profile, Duration timeout, Observer observer) {
        return exchange(method, uri, form, ajax, projectId, timeout, observer);
    }
    /**
     * multipart/form-data загрузка файла (C07 вложений чата, захват 15.11.2026:
     * POST https://f2.dikidi.ru/upload/?v=2, поле «file»). Один part, байты целиком
     * в памяти вызывающего (размер ограничивает вызывающий). Дефолт — не поддержано:
     * upload умеют только профилированные реализации.
     */
    default Response upload(URI uri, String fieldName, String fileName, String contentType, byte[] content,
                            String projectId, RequestProfile profile, Duration timeout, Observer observer) {
        throw new UnsupportedOperationException("upload is not supported by this transport");
    }
    interface Observer {
        default void beforeSend() {}
        default void dispatched() {}
        default void response(Response response) {}
    }
    @Override default void close() {}
}
