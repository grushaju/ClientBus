package kit.penny.clientbus.crm.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Канонический вид ссылки на файл провайдера (аватар карточки). Нужен, чтобы хост никогда не
 * видел сырую строку из разметки: приёмлем только https-файл на cdn провайдера, иначе — null.
 * Это украшение, а не идентификатор: по совпадению файла связь никогда не устанавливается.
 */
public final class CrmAvatarUrl {
    /** Хосты cdn провайдера: f1|f2.dikidi.ru|net|app и сам dikidi.ru без поддомена. */
    private static final Set<String> HOSTS = Set.of("dikidi.ru", "dikidi.net", "dikidi.app");
    private static final Pattern FILE = Pattern.compile(
            "^/[a-z0-9_.-]{1,40}(?:/[a-z0-9_.-]{1,40}){0,5}/[a-z0-9_-]{3,80}\\.(?:jpg|jpeg|png|gif|webp)$",
            Pattern.CASE_INSENSITIVE);

    private CrmAvatarUrl() { }

    /** Абсолютный https-url файла или null. Хост каноничен в нижнем регистре, query отброшен. */
    public static String canonical(String raw) {
        if (raw == null) return null;
        var value = raw.strip();
        if (value.isEmpty() || value.length() > 512 || value.codePoints().anyMatch(Character::isWhitespace)) return null;
        if (value.startsWith("//")) value = "https:" + value;
        else if (value.startsWith("/")) value = "https://dikidi.ru" + value;
        var uri = parse(value);
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPath() == null) return null;
        // Зеркала cdn (f1., f2., …) значимы только как хост: новое зеркало провайдера не должно
        // молча лишать карточку аватара, поэтому префикс любой цифры, а не перечисление.
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        var zone = host.replaceFirst("^f[0-9]{1,3}\\.", "");
        if (!HOSTS.contains(zone) || !FILE.matcher(uri.getPath()).matches()) return null;
        // Форма пути проверяется по ДЕКОДИРОВАННОМУ значению: %2e%2e становится «..» и отбивается
        // здесь же. Хранить и скачивать путь с переходом вверх мы не будем ни в каком виде.
        for (var segment : uri.getPath().split("/")) if (segment.equals(".") || segment.equals("..")) return null;
        // Ключ пути кладётся в crm.client.avatarorigin (varchar 200): путь длиннее лимита не просто
        // не влезет — он уронил бы всю страницу списка ошибкой колонки, поэтому отклоняем здесь.
        if (uri.getPath().length() > 190) return null;
        return "https://" + host + uri.getPath();
    }

    /** Ключ файла для сравнения «тот же файл или другой»: хост и расширение не значимы. */
    public static String key(String canonical) {
        if (canonical == null) return null;
        var authority = canonical.indexOf("//") + 2;
        var path = canonical.substring(canonical.indexOf('/', authority));
        var dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(0, dot) : path;
    }

    private static java.net.URI parse(String value) {
        try {
            var uri = new java.net.URI(value);
            return uri.isAbsolute() && uri.getUserInfo() == null && uri.getPort() < 0 ? uri : null;
        } catch (java.net.URISyntaxException failure) {
            return null;
        }
    }
}
