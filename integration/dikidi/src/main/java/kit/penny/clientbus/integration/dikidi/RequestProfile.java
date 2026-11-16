package kit.penny.clientbus.integration.dikidi;

/**
 * Профиль запроса (13.4): адресность, Origin/Referer и проверка назначения
 * различаются для CRM-владельца и owner-чатов. Это заменяет «CRM Referer для
 * всего»: чат больше не притворяется журналом.
 */
public enum RequestProfile {
    /** dikidi.ru/auth.dikidi.ru, Referer по правилам CRM (auth, projects list, journal). */
    CRM,
    /** chat.dikidi.ru API + dikidi.ru shell-страница /ru/owner/chat/. */
    OWNER_CHAT,
    /**
     * CDN вложений чата (15.11.2026: joining «image» отдаёт путь c51/v50697/…,
     * скачивается как https://f2.dikidi.ru/{путь}.jpg?size=b; 16.11.2026: иконки
     * диалогов C02 живут на том же семействе зеркал, но в зоне dikidi.net —
     * например https://f2.dikidi.net/cNN/vNNNNN/<hash>.jpg). Только GET картинок:
     * хост-зеркало fN.dikidi.ru / fN.dikidi.net, путь — без точек и сегментных
     * трюков, обязательное окончание .jpg (иначе профиль пустил бы и GET /upload/).
     */
    MEDIA,
    /**
     * Загрузка файла чата (C07, захват пользователя 15.11.2026): POST
     * https://f2.dikidi.ru/upload/?v=2 multipart-полем «file». Ровно этот путь,
     * только зеркало fN.dikidi.ru — загрузка не должна уехать никуда иначе.
     */
    UPLOAD;

    boolean allows(String host) {
        return allows(host, "/");
    }

    /**
     * Ревю 13.4 (бэклог 13.5): path-гранулярность как defense-in-depth — профиль
     * OWNER_CHAT не открывает dikidi.ru целиком, только саму оболочку owner-чата
     * (страница и C01 ajax/page); CRM-профиль не сужается (у него широкий рабочий
     * набор ajax-эндпоинтов, сужение — отдельное решение вне чата).
     */
    boolean allows(String host, String path) {
        String normalized = path == null || path.isEmpty() ? "/" : path;
        return switch (this) {
            case CRM -> host.equals("dikidi.ru") || host.equals("auth.dikidi.ru");
            case OWNER_CHAT -> (host.equals("chat.dikidi.ru") && normalized.startsWith("/api/"))
                    || (host.equals("dikidi.ru") && normalized.startsWith("/ru/owner/chat"));
            // Ревю 15.11.2026: прежний класс [A-Za-z0-9/_-] не пропускал точку живого пути
            // c51/v50697/23s40uub39.jpg (фейки guard не проходят — тесты не видели). Теперь
            // обязательно окончание .jpg и ровно одна точка перед ним.
            // 16.11.2026: то же CDN-семейство в зоне dikidi.net (иконки диалогов).
            case MEDIA -> (host.matches("f[0-9]+\\.dikidi\\.ru") || host.matches("f[0-9]+\\.dikidi\\.net"))
                    && normalized.matches("/[A-Za-z0-9][A-Za-z0-9/_-]{2,154}\\.jpg");
            case UPLOAD -> host.matches("f[0-9]+\\.dikidi\\.ru") && normalized.equals("/upload/");
        };
    }
}
