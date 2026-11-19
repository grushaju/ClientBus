package kit.penny.clientbus.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило 13.1 о границе коннекторов, распространённое на реализации (план 13.1, пункт про
 * «из common в connector/api и его реализации импортируются только enum-типы и константы»;
 * до этого тест закрывал только сам connector/api, а отступление реализаций было записано
 * словами в Stage 13.txt).
 *
 * DIKIDI-реализация написана по этому правилу с нуля и держится чисто. Telegram-наследие
 * легализовано поимённо и по количеству: список ниже замораживает отступление — новый
 * транспортный DTO сюда не протискивается, а когда очередное отступление рассасывается,
 * об этом сообщает отдельная проверка, чтобы список не превращался в кладбище.
 *
 * <p>Правило смотрит на код, а не на оформление. Комментарии вычищаются: строка
 * {@code // import ...} не должна становиться нарушением. Запрет проверяется по всему тексту
 * файла, потому что полностью квалифицированное имя без строки импорта — ровно тот же самый
 * доступ к чужому модулю. Модули реализаций находятся списком каталогов: молча отсутствовать в
 * перечне означало бы «не проверяется».
 */
class ConnectorImplementationArchitectureTest {

    private static final Map<String, List<Path>> IMPLEMENTATIONS = modules();

    /** Имена разрешены целиком и с границей: у префикса без границы любое имя «разрешено». */
    private static final List<String> ALLOWED_COMMON = List.of(
            "kit.penny.clientbus.common.enums.",
            "kit.penny.clientbus.common.notification.EventOrigin");

    private static final Pattern HOST_REFERENCE =
            Pattern.compile("kit\\.penny\\.clientbus\\.server\\.[A-Za-z0-9_.]+");
    private static final Pattern COMMON_REFERENCE =
            Pattern.compile("kit\\.penny\\.clientbus\\.common\\.[A-Za-z0-9_.]+");
    private static final Pattern PERSISTENCE_REFERENCE =
            Pattern.compile("jakarta\\.persistence\\.[A-Za-z0-9_.]+");

    /** Узаконенное отступление Telegram: эти импорты и ровно в этом количестве. */
    private static final Map<String, Integer> LEGACY_TELEGRAM = Map.of(
            "kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest", 3,
            "kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto", 2,
            "kit.penny.clientbus.common.dto.channel.ClientAccountAvatarPreviewDto", 1,
            "jakarta.persistence.EntityNotFoundException", 2);

    /**
     * Реализации находятся обходом каталогов. Модули контрактов (с окончанием api) исключены:
     * у них своё правило — ConnectorApiArchitectureTest, а транспортные DTO им положены по
     * определению.
     */
    private static Map<String, List<Path>> modules() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (var parent : List.of("connector", "integration", "crm")) {
            var root = Path.of("..", parent);
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("каталог модулей коннекторов не найден из модуля server: "
                        + root.toAbsolutePath());
            }
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(child -> {
                    var name = child.getFileName().toString();
                    var sources = child.resolve(Path.of("src", "main", "java"));
                    if (name.endsWith("api") || !Files.isDirectory(sources)) {
                        return;
                    }
                    try (var paths = Files.walk(sources)) {
                        result.put(parent + "/" + name,
                                paths.filter(path -> path.toString().endsWith(".java")).toList());
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        assertTrue(result.keySet().containsAll(
                        List.of("connector/telegram", "connector/dikidi", "integration/dikidi", "crm/dikidi")),
                "знакомые реализации коннекторов не найдены — правило проверяет пустоту: " + result.keySet());
        return result;
    }

    /** Исходник без комментариев: правило про код, а не про то, что в нём написано. */
    private static String code(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\\n]*", " ");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<String> imports(List<Path> sources) {
        var result = new ArrayList<String>();
        for (var source : sources) {
            for (var line : code(source).lines().toList()) {
                var text = line.trim();
                if (!text.startsWith("import")) {
                    continue;
                }
                result.add(text.replaceFirst("^import\\s+(static\\s+)?", "").replaceFirst(";\\s*$", "").trim());
            }
        }
        return result;
    }

    /** Обращения к чужому пакету в исходнике: имя целиком, с именем файла в сообщении. */
    private static List<String> references(Pattern pattern, List<Path> sources) {
        var found = new ArrayList<String>();
        for (var source : sources) {
            var matcher = pattern.matcher(code(source));
            while (matcher.find()) {
                found.add(source.getFileName() + " → " + matcher.group());
            }
        }
        return found;
    }

    /** Разрешено содержимое пакета enums и класс EventOrigin со его членами. */
    private static boolean allowed(String reference) {
        return ALLOWED_COMMON.stream().anyMatch(prefix -> namedAs(reference, prefix));
    }

    /**
     * Совпадение по префиксу засчитывается только на границе имени: иначе разрешённый
     * EventOrigin пропускал бы EventOriginЛюбоеДругоеИмя, а пакет без точки на конце —
     * любое имя с тем же началом.
     */
    private static boolean namedAs(String reference, String name) {
        if (!reference.startsWith(name)) {
            return false;
        }
        return name.endsWith(".") || reference.length() == name.length()
                || !isNameChar(reference.charAt(name.length()));
    }

    private static boolean isNameChar(char symbol) {
        return Character.isLetterOrDigit(symbol) || symbol == '_';
    }

    /** Реализация коннектора не знает про host: порты описаны в connector/api. */
    @Test void implementationsDoNotReferenceHostClasses() {
        var violations = new ArrayList<String>();
        for (var entry : IMPLEMENTATIONS.entrySet()) {
            for (var type : imports(entry.getValue())) {
                if (type.startsWith("kit.penny.clientbus.server.")) {
                    violations.add(entry.getKey() + ": " + type);
                }
            }
            references(HOST_REFERENCE, entry.getValue()).forEach(reference ->
                    violations.add(entry.getKey() + "/" + reference));
        }
        assertTrue(violations.isEmpty(),
                () -> "реализации коннекторов обращаются к классам host-модуля: " + violations);
    }

    /** Транспортные и REST DTO из common, а также JPA — только в узаконенном списке. */
    @Test void implementationsDoNotReferenceTransportDataTransferObjects() {
        var violations = new ArrayList<String>();
        for (var entry : IMPLEMENTATIONS.entrySet()) {
            for (var type : imports(entry.getValue())) {
                boolean transportDataTransferObject = type.startsWith("kit.penny.clientbus.common.")
                        && ALLOWED_COMMON.stream().noneMatch(type::startsWith);
                boolean persistenceFramework = type.startsWith("jakarta.persistence.");
                if ((transportDataTransferObject || persistenceFramework)
                        && !LEGACY_TELEGRAM.containsKey(type)) {
                    violations.add(entry.getKey() + ": " + type);
                }
            }
            for (var reference : references(COMMON_REFERENCE, entry.getValue())) {
                var name = reference.substring(reference.indexOf('→') + 2);
                if (!allowed(name) && LEGACY_TELEGRAM.keySet().stream().noneMatch(legacy -> namedAs(name, legacy))) {
                    violations.add(entry.getKey() + "/" + reference);
                }
            }
            for (var reference : references(PERSISTENCE_REFERENCE, entry.getValue())) {
                var name = reference.substring(reference.indexOf('→') + 2);
                if (LEGACY_TELEGRAM.keySet().stream().noneMatch(legacy -> namedAs(name, legacy))) {
                    violations.add(entry.getKey() + "/" + reference);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "реализации коннекторов используют транспортные DTO или кадры персистентности: " + violations);
    }

    /** Легализованное отступление Telegram не разрастается: и состав, и количество. */
    @Test void telegramLegacyListIsFrozenExactly() {
        var actual = new LinkedHashMap<String, Integer>();
        for (var type : imports(IMPLEMENTATIONS.get("connector/telegram"))) {
            if (LEGACY_TELEGRAM.containsKey(type)) {
                actual.merge(type, 1, Integer::sum);
            }
        }
        assertEquals(LEGACY_TELEGRAM, actual,
                "список узаконенных отступлений Telegram разошёлся с исходниками: обновите и код, и запись в Stage 13.txt");
    }

    /**
     * Проверка обязана видеть то, что собирается запрещать (ссылку без строки импорта), и не
     * видеть того, чего в коде нет (импорт, оставленный в комментарии). Файл пишется во
     * временный каталог: источник, который не компилируется, нельзя оставлять в дереве модуля.
     */
    @Test void commentsDoNotHideAViolationAndQualifiedNameIsAViolationToo() throws IOException {
        var directory = Files.createTempDirectory("connector-architecture-probe");
        var probe = directory.resolve("ArchitectureGuardProbe.java");
        try {
            Files.writeString(probe, """
                    package kit.penny.clientbus.connector.telegram;

                    // import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
                    import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;

                    final class ArchitectureGuardProbe {
                        Class<?> qualifiedWithoutImport() {
                            return kit.penny.clientbus.server.service.MessageService.class;
                        }
                    }
                    """, StandardCharsets.UTF_8);

            var imported = imports(List.of(probe));
            assertFalse(imported.contains("kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto"),
                    "закомментированный импорт засчитан как импорт — правило наказывает за текст комментария");
            assertTrue(imported.contains("kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest"),
                    "настоящий импорт перестал быть виден — правило ничего не видит");
            assertEquals(List.of("ArchitectureGuardProbe.java → kit.penny.clientbus.server.service.MessageService.class"),
                    references(HOST_REFERENCE, List.of(probe)),
                    "ссылка на host без строки импорта осталась незамеченной");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(directory);
        }
    }
}
