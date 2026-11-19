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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 */
class ConnectorImplementationArchitectureTest {

    private static final Map<String, List<Path>> IMPLEMENTATIONS = modules();

    /** Разрешённые обращения реализаций к common: только enum-типы и константы. */
    private static final List<String> ALLOWED_COMMON = List.of(
            "kit.penny.clientbus.common.enums.",
            "kit.penny.clientbus.common.notification.EventOrigin");

    /** Узаконенное отступление Telegram: эти импорты и ровно в этом количестве. */
    private static final Map<String, Integer> LEGACY_TELEGRAM = Map.of(
            "kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest", 3,
            "kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto", 2,
            "kit.penny.clientbus.common.dto.channel.ClientAccountAvatarPreviewDto", 1,
            "jakarta.persistence.EntityNotFoundException", 2);

    private static Map<String, List<Path>> modules() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (var module : List.of(
                List.of("connector", "telegram"), List.of("connector", "dikidi"),
                List.of("integration", "dikidi"), List.of("crm", "dikidi"))) {
            var root = Path.of("..", module.get(0), module.get(1), "src", "main", "java");
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("источники модуля коннектора не найдены из модуля server: "
                        + root.toAbsolutePath());
            }
            try (var paths = Files.walk(root)) {
                result.put(String.join("/", module), paths.filter(path -> path.toString().endsWith(".java")).toList());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return result;
    }

    private static List<String> imports(List<Path> sources) throws IOException {
        var result = new ArrayList<String>();
        for (var source : sources) {
            for (var line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                var text = line.trim();
                if (!text.startsWith("import")) {
                    continue;
                }
                result.add(text.replaceFirst("^import\\s+(static\\s+)?", "").replaceFirst(";\\s*$", "").trim());
            }
        }
        return result;
    }

    /** Реализация коннектора не знает про host: порты описаны в connector/api. */
    @Test void implementationsDoNotImportHostClasses() throws IOException {
        var violations = new ArrayList<String>();
        for (var entry : IMPLEMENTATIONS.entrySet()) {
            for (var type : imports(entry.getValue())) {
                if (type.startsWith("kit.penny.clientbus.server.")) {
                    violations.add(entry.getKey() + ": " + type);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "реализации коннекторов импортируют классы host-модуля: " + violations);
    }

    /** Транспортные и REST DTO из common, а также JPA — только в узаконенном списке. */
    @Test void implementationsDoNotImportTransportDataTransferObjects() throws IOException {
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
        }
        assertTrue(violations.isEmpty(), () -> "реализации коннекторов импортируют транспортные DTO или JPA: " + violations);
    }

    /** Легализованное отступление Telegram не разрастается: и состав, и количество. */
    @Test void telegramLegacyListIsFrozenExactly() throws IOException {
        var actual = new LinkedHashMap<String, Integer>();
        for (var type : imports(IMPLEMENTATIONS.get("connector/telegram"))) {
            if (LEGACY_TELEGRAM.containsKey(type)) {
                actual.merge(type, 1, Integer::sum);
            }
        }
        assertEquals(LEGACY_TELEGRAM, actual,
                "список узаконенных отступлений Telegram разошёлся с исходниками: обновите и код, и запись в Stage 13.txt");
    }

    /** Список не должен переживать собственные рассосавшиеся отступления: об этом
     * сообщает проверка выше — любое расхождение состава или количества делает её красной. */
}
