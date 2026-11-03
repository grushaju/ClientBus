package kit.penny.clientbus.server.crm;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Literal search, including SQL LIKE metacharacters. Cursor scope never contains user text. */
final class CrmSearch {
    private CrmSearch() {}
    static String normalize(String value) {
        if (value == null) return "";
        if (value.length() > 160) throw CrmException.invalid();
        return value.strip().toLowerCase(Locale.ROOT);
    }
    static String pattern(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    static String scope(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
}
