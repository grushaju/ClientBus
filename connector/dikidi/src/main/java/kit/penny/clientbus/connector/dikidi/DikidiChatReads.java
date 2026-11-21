package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import kit.penny.clientbus.integration.dikidi.RequestProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.INVALID_RESPONSE;
import static kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.RATE_LIMITED;
import static kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.TEMPORARY_FAILURE;

/**
 * Чтение протокола owner-чата DIKIDI (13.5): C01-оболочка как предпосылка
 * сессии, C02 список диалогов, C03 непрочитанное, C04 участники, C05 страницы
 * сообщений. Только GET; каждый вызов идёт через общий DikidiClient в профиле
 * OWNER_CHAT (те же сессия, token-бакет и lease-guard, что у CRM). Формы
 * ответов зафиксированы CRM_DIKIDI_PROTOCOL.md (C01–C05, повтор 13.11.2026):
 * числовой epoch в date, числовой флаг out, пагинация C05 курсором last по
 * самому старому сообщению, пустая страница = начало истории.
 */
public final class DikidiChatReads {
    private static final Logger log = LoggerFactory.getLogger(DikidiChatReads.class);
    private static final JsonMapper JSON = new JsonMapper();
    /** C05 limit проверен пробником на 20; большие значения протоколом не подтверждены. */
    public static final int MAX_PAGE = 20;

    /** Диалог из C02: id числовая строка, date — epoch провайдера (таймзона не различима, §protocol). */
    public record Dialog(String id, String name, String icon, String lastText, long dateEpoch, int badge) {}
    public record DialogsPage(List<Dialog> dialogs, boolean more) {}
    /**
     * Сообщение из C04/C05. out — автор учётной записи проекта. joining — ПРИКРЕПЛЁННЫЕ
     * файлы (запись [вид, payload, код]; «image» — JSON-строка [_, fileId, путь, w, h],
     * живые примеры 15.11.2026), а не служебная врезка: текст при вложениях
     * остаётся текстом сообщения. read/seen (13.6, по коду веб-клиента): у исходящих
     * read>0 — собеседник прочитал (счётчик читателей); у входящих seen — просмотрено
     * салоном (именно его ставит C08-отметка). authorIcon — user_icon автора: у ответа C06
     * это автор НАШЕЙ сессии, то есть владелец подключения (аватар канала), а не любой
     * out=1: сторон support в диалоге бывает несколько.
     */
    public record Message(String id, long epoch, String userId, String userName, String text, boolean out,
            List<Attachment> attachments, int read, int seen, String authorIcon) {}

    /** Вложение joining: вид («image», …) и скачиваемый MEDIA-url, если форма доказана. */
    public record Attachment(String kind, String url) {}
    public record MessagesPage(List<Message> messages, boolean historyStartReached) {}

    /**
     * Предпосылка обращения к chat API: без предварительного визита страницы owner-чата
     * все запросы chat.dikidi.ru отвечают 400 при валидной сессии (доказано 13.1).
     * Дёшево: один GET на логическую операцию, кэш здесь противоречил бы Evidence-семантике.
     */
    public String shell(DikidiClient.Session session, String company) {
        var response = session.exchange("GET", "https://dikidi.ru/ru/owner/chat/?company=" + enc(company),
                List.of(), false, company, RequestProfile.OWNER_CHAT, null);
        if (response.status() != 200) throw mapStatus(response.status());
        return DikidiOwnerAvatar.parse(response.body());
    }

    /**
     * C02: time=0 — свежая страница; time>0 — курсор назад (живой смысл параметра не
     * доказан, поэтому обход в коннекторе защищён дедупом по id и остановкой без прогресса).
     */
    public DialogsPage dialogs(DikidiClient.Session session, String company) {
        return dialogs(session, company, 0L);
    }

    public DialogsPage dialogs(DikidiClient.Session session, String company, long timeCursor) {
        var data = ok(session, "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company="
                + enc(company) + "&time=" + timeCursor + "&msg=true", company);
        var list = data.path("list");
        if (!list.isArray()) throw new DikidiFailure(INVALID_RESPONSE);
        var result = new ArrayList<Dialog>();
        for (var dialog : list) {
            String id = dialog.path("id").asString("");
            if (!id.matches("[1-9][0-9]{0,17}")) continue; // нечисловой id — не разбираем по догадке
            result.add(new Dialog(id, dialog.path("name").asString(null), dialog.path("icon").asString(null),
                    lastMessageText(dialog.path("msg")), epoch(dialog.path("date")),
                    dialog.path("badge").isNumber() ? dialog.path("badge").asInt() : 0));
        }
        return new DialogsPage(List.copyOf(result), truthy(data.path("more")));
    }

    /** C03: форма map dialogId→число; total не является суммой сообщений и не используется. */
    public Map<String, Integer> unread(DikidiClient.Session session, String company) {
        var data = ok(session, "https://chat.dikidi.ru/api/dialogs/unreaded/?interface=owner&company="
                + enc(company), company);
        var result = new LinkedHashMap<String, Integer>();
        if (data.isObject()) for (var entry : data.properties())
            if (entry.getValue().isNumber()) result.put(entry.getKey(), entry.getValue().asInt());
        return Map.copyOf(result);
    }

    /** id → имя (классификация пользуется participantsDetailed с role). */
    public Map<String, String> participants(DikidiClient.Session session, String dialogId, String company) {
        var names = new LinkedHashMap<String, String>();
        participantsDetailed(session, dialogId, company).forEach((id, p) -> names.put(id, p.name()));
        return Map.copyOf(names);
    }

    /**
     * C04 участники. Форма дрейфует: массив скаляров/объектов (13.11.2026, role не читался)
     * и объект-обёртка {"list": {id: {…}}, "total": n} с role/is_open_session (15.11.2026);
     * обе принимаем, нераспознанную форму считаем «участники не разрешимы» (пустое множество
     * → честный skip классификатором), а не рвём синхронизацию всего канала.
     * role возвращается как есть и может отсутствовать — классификатор обязан это переживать.
     */
    public Map<String, Participant> participantsDetailed(DikidiClient.Session session, String dialogId, String company) {
        var data = ok(session, "https://chat.dikidi.ru/api/dialog/get/?dialog=" + enc(dialogId)
                + "&messages=1&users=1", company);
        var users = data.path("users");
        var result = new LinkedHashMap<String, Participant>();
        if (users.isArray()) {
            for (var user : users) {
                String id = user.isObject() ? user.path("id").asString("") : user.asString("");
                if (!id.matches("[1-9][0-9]{0,17}")) continue;
                String name = user.isObject() ? user.path("name").asString(null) : null;
                String role = user.isObject() ? blankToNull(user.path("role").asString(null)) : null;
                result.putIfAbsent(id, new Participant(name == null ? "" : name, role));
                // Map.copyOf не терпит null: скалярный участник — без имени
            }
        } else if (users.isObject()) {
            var map = users.path("list").isObject() ? users.path("list") : users;
            for (var entry : map.properties()) {
                if (!entry.getKey().matches("[1-9][0-9]{0,17}")) continue;
                var value = entry.getValue();
                String name = value.isObject() ? value.path("name").asString(null)
                        : value.isString() ? value.asString(null) : null;
                String role = value.isObject() ? blankToNull(value.path("role").asString(null)) : null;
                result.putIfAbsent(entry.getKey(), new Participant(name == null ? "" : name, role));
            }
        } else if (!users.isNull() && !users.isMissingNode())
            log.warn("DIKIDI C04 users has unexpected shape: dialog={}, kind={}", dialogId, users.getNodeType());
        return Map.copyOf(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Участник C04: имя (может быть пустым) и роль, если читается (support/user/… — дрейф). */
    public record Participant(String name, String role) {}

    /** Страница C05: last=null — свежайшие; курсор last — более старые. Пустая страница = начало истории. */
    public MessagesPage messages(DikidiClient.Session session, String dialogId, String last, int limit, String company) {
        int size = Math.clamp(limit, 1, MAX_PAGE);
        var data = ok(session, "https://chat.dikidi.ru/api/dialog/messages/?dialog=" + enc(dialogId)
                + "&limit=" + size + (last == null ? "" : "&last=" + enc(last)) + "&msg_full=true", company);
        var list = data.path("list").isArray() ? data.path("list") : data.path("messages");
        if (!list.isArray()) throw new DikidiFailure(INVALID_RESPONSE);
        var result = new ArrayList<Message>();
        for (var message : list) {
            String id = message.path("id").asString("");
            if (!id.matches("[0-9]{1,18}")) continue;
            result.add(new Message(id, epoch(message.path("date")), message.path("user_id").asString(""),
                    message.path("user_name").asString(null), message.path("text").asString(""),
                    "1".equals(message.path("out").asString("")), attachments(message.path("joining")),
                    message.path("read").asInt(0), message.path("seen").asInt(0),
                    blankToNull(message.path("user_icon").asString(null))));
        }
        return new MessagesPage(List.copyOf(result), result.isEmpty());
    }

    /**
     * joining — массив записей [вид, payload, код]. Доказан «image» с JSON-строкой
     * payload [_, fileId, "cNN/vNNNNN/hash", w, h] → https://f2.dikidi.ru/{путь}.jpg?size=b
     * (пользовательский пример 15.11.2026). Иные виды и неразложимая форма — вложение
     * без url: сообщение импортируется, контент не угадывается.
     */
    private static List<Attachment> attachments(JsonNode joining) {
        if (!joining.isArray() || joining.isEmpty()) return List.of();
        var result = new ArrayList<Attachment>();
        for (var entry : joining) {
            if (!entry.isArray() || entry.isEmpty()) continue;
            String kind = entry.get(0).asString("").toLowerCase(Locale.ROOT);
            if (kind.isBlank()) continue;
            result.add(new Attachment(kind, "image".equals(kind) && entry.size() > 1 ? imageUrl(entry.get(1)) : null));
        }
        return List.copyOf(result);
    }

    private static String imageUrl(JsonNode payload) {
        try {
            String path = JSON.readTree(payload.asString("")).path(2).asString("");
            return path.matches("[A-Za-z0-9][A-Za-z0-9/_-]{2,150}") && !path.contains("..")
                    ? "https://f2.dikidi.ru/" + path + ".jpg?size=b" : null;
        } catch (Exception malformed) {
            return null; // неразложимый payload — не угадываем
        }
    }

    /** Бинарное скачивание MEDIA (CDN картинок чата). */
    public byte[] media(DikidiClient.Session session, String url, String company) {
        var response = session.exchange("GET", url, List.of(), true, company, RequestProfile.MEDIA, null);
        if (response.status() == 429) throw new DikidiFailure(RATE_LIMITED);
        if (response.status() >= 500) throw new DikidiFailure(TEMPORARY_FAILURE);
        if (response.status() != 200 || response.binary() == null || response.binary().length == 0)
            throw new DikidiFailure(INVALID_RESPONSE);
        return response.binary();
    }

    /**
     * Результат C06: accepted=false — только явно доказанный отказ платформы (error.code≠0).
     * authorExternalId/authorIcon — user_id и user_icon сохранённого сообщения: их прислала
     * платформа по НАШЕЙ сессии, значит это и есть владелец подключения (аватар канала).
     */
    public record SendOutcome(boolean accepted, String externalId, int errorCode,
            String authorExternalId, String authorIcon) {}

    /**
     * C06 отправка (захват пользователя 15.11.2026): POST https://chat.dikidi.ru/api/dialog/message/;
     * принятый ответ — error.code=0 и полное сообщение с присвоенным id и out=1.
     * HTTP-отказы маппятся mapStatus (400/401 → AUTH_REQUIRED); вызывающий обязан идти через
     * executeOnce: повтор с перелогинированием перечитал бы и переотправил намерение (дубликат).
     * error.code≠0 до отправки эффекта — отказ без внешнего эффекта. code=0, но форма ответа
     * поехала (нет id, out≠1) — исход НЕИЗВЕСТЕН: INVALID_RESPONSE, клейм удерживается.
     */
    public SendOutcome sendMessage(DikidiClient.Session session, String company,
            List<Map.Entry<String, String>> form) {
        return sendMessage(session, company, form, null);
    }

    public SendOutcome sendMessage(DikidiClient.Session session, String company,
            List<Map.Entry<String, String>> form,
            kit.penny.clientbus.integration.dikidi.DikidiTransport.Observer observer) {
        var response = session.exchange("POST", "https://chat.dikidi.ru/api/dialog/message/", form,
                true, company, RequestProfile.OWNER_CHAT, observer);
        if (response.status() != 200) throw mapStatus(response.status());
        JsonNode root;
        try { root = JSON.readTree(response.body()); }
        catch (RuntimeException malformed) { throw new DikidiFailure(INVALID_RESPONSE); }
        var error = root.path("error");
        String code = error.isObject() ? error.path("code").asString("0") : error.asString("0");
        if (!"0".equals(code) && !code.isEmpty())
            return new SendOutcome(false, null, code.matches("-?[0-9]+") ? Integer.parseInt(code) : -1, null, null);
        var data = root.path("data");
        String id = data.path("id").asString("");
        if (!id.matches("[0-9]+") || !"1".equals(data.path("out").asString("")))
            throw new DikidiFailure(INVALID_RESPONSE);
        return new SendOutcome(true, id, 0, blankToNull(data.path("user_id").asString(null)),
                blankToNull(data.path("user_icon").asString(null)));
    }

    /** Файл, принятый C07-загрузкой: готовый источник вхождения joining. */
    public record Uploaded(String fileId, String code, String path, int width, int height, long size) {}

    /**
     * C07 загрузка файла чата (захват пользователя 15.11.2026): POST
     * https://f2.dikidi.ru/upload/?v=2 multipart-полем «file»; ответ
     * data = [fileId, «image», url, код, w, h, имя, расширение, размер]. Хост f2 —
     * наблюдение захвата; профиль UPLOAD разрешает любое зеркало fN того же пути.
     * Отказ загрузки (error.code≠0 или неразобранная форма) — DikidiFailure: сообщение
     * ещё не отправлялось, внешнего эффекта на переписку нет.
     */
    public Uploaded uploadImage(DikidiClient.Session session, String company, String fileName,
            String contentType, byte[] content) {
        var response = session.upload("https://f2.dikidi.ru/upload/?v=2", "file", fileName, contentType,
                content, company, RequestProfile.UPLOAD);
        if (response.status() != 200) throw mapStatus(response.status());
        JsonNode root;
        try { root = JSON.readTree(response.body()); }
        catch (RuntimeException malformed) { throw new DikidiFailure(INVALID_RESPONSE); }
        var error = root.path("error");
        String code = error.isObject() ? error.path("code").asString("0") : error.asString("0");
        if (!"0".equals(code) && !code.isEmpty()) throw new DikidiFailure(INVALID_RESPONSE);
        var data = root.path("data");
        if (!data.isArray() || data.size() < 6) throw new DikidiFailure(INVALID_RESPONSE);
        String fileId = data.path(0).asString("");
        String url = data.path(2).asString("");
        String uploadCode = data.path(3).asString("");
        if (!fileId.matches("[0-9]{1,18}") || !uploadCode.matches("[A-Za-z0-9]{1,32}"))
            throw new DikidiFailure(INVALID_RESPONSE);
        java.util.regex.Matcher path = java.util.regex.Pattern
                .compile("https://f[0-9]+\\.dikidi\\.ru/([A-Za-z0-9][A-Za-z0-9/_-]{2,150})\\.jpg").matcher(url);
        if (!path.matches()) throw new DikidiFailure(INVALID_RESPONSE);
        return new Uploaded(fileId, uploadCode, path.group(1),
                data.path(4).asInt(0), data.path(5).asInt(0),
                data.size() > 8 ? data.path(8).asLong(content.length) : content.length);
    }

    /**
     * tabHash чата: веб-клиент генерирует случайную base36-строку на вкладку и шлёт её
     * с каждым изменяющим запросом; push-события со своим tabHash вкладка игнорирует
     * (backend.js getHash). ClientBus вкладкой не является — идентификатор нужен только
     * для соответствия форме запроса, генерируется один на JVM.
     */
    public static final String TAB_HASH =
            Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36);

    /**
     * C08 отметка прочтения (захват публичного backend.js 15.11.2026, POST
     * /api/dialog/read/ {tabHash, dialog, msg[]}): msg — конкретные id ВХОДЯЩИХ
     * просмотренных сообщений (веб-клиент отправляет id из видимой области прокрутки).
     * Идемпотентна; повтор безопасен — допускается auth-повтор на уровне клиента.
     */
    public void markRead(DikidiClient.Session session, String company, String dialogId, List<String> messageIds) {
        if (messageIds.isEmpty()) return;
        var form = new ArrayList<Map.Entry<String, String>>();
        form.add(Map.entry("tabHash", TAB_HASH));
        form.add(Map.entry("dialog", dialogId));
        for (var id : messageIds) form.add(Map.entry("msg[]", id));
        var response = session.exchange("POST", "https://chat.dikidi.ru/api/dialog/read/", form, true,
                company, RequestProfile.OWNER_CHAT, null);
        if (response.status() != 200) throw mapStatus(response.status());
        JsonNode root;
        try { root = JSON.readTree(response.body()); }
        catch (RuntimeException malformed) { throw new DikidiFailure(INVALID_RESPONSE); }
        var error = root.path("error");
        String code = error.isObject() ? error.path("code").asString("0") : error.asString("0");
        if (!"0".equals(code) && !code.isEmpty()) throw new DikidiFailure(INVALID_RESPONSE);
    }

    private JsonNode ok(DikidiClient.Session session, String address, String company) {
        var response = session.exchange("GET", address, List.of(), true, company, RequestProfile.OWNER_CHAT, null);
        if (response.status() != 200) throw mapStatus(response.status());
        JsonNode root;
        try { root = JSON.readTree(response.body()); }
        catch (RuntimeException malformed) { throw new DikidiFailure(INVALID_RESPONSE); }
        var error = root.path("error");
        String code = error.isObject() ? error.path("code").asString("0") : error.asString("0");
        if (!"0".equals(code) && !code.isEmpty()) throw new DikidiFailure(INVALID_RESPONSE);
        return root.path("data");
    }

    /**
     * Единственный доказанный смысл HTTP 400 на chat.dikidi.ru — «Недостаточно прав»
     * без предпосылки-визита (или потерянная чат-сессия): маппим в AUTH_REQUIRED,
     * чтобы execute() ровно один раз долечил пере-входом с новым shell-визитом.
     */
    private static DikidiFailure mapStatus(int status) {
        if (status == 400 || status == 401) return new DikidiFailure(DikidiFailure.Code.AUTH_REQUIRED);
        if (status == 403) return new DikidiFailure(DikidiFailure.Code.ACCESS_DENIED);
        if (status == 429) return new DikidiFailure(DikidiFailure.Code.RATE_LIMITED);
        if (status >= 500) return new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE);
        return new DikidiFailure(INVALID_RESPONSE);
    }

    /** C02 msg встречается и строкой, и объектом сообщения — превью берём по обеим формам. */
    private static String lastMessageText(JsonNode msg) {
        if (msg.isTextual()) return msg.asString("");
        if (msg.isObject()) return msg.path("text").asString("");
        return "";
    }

    private static long epoch(JsonNode date) {
        if (date.isNumber()) return date.asLong();
        String text = date.asString("");
        if (text.matches("[0-9]{9,11}")) return Long.parseLong(text);
        return 0; // неразличимая форма — не угадываем
    }

    private static boolean truthy(JsonNode value) {
        return value.isBoolean() ? value.asBoolean() : Set.of("1", "true").contains(value.asString(""));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
