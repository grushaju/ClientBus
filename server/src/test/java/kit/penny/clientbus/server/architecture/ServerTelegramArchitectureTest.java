package kit.penny.clientbus.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 13.3 build rule: the Telegram connector lives in its own Maven module and the
 * server core stays Telegram-free.
 *
 * <p>Both directions are checked over sources (same approach as
 * {@link ConnectorApiArchitectureTest}): connector/telegram production code may not import
 * anything from the server module, and server production code may not import Telegram/TDLib
 * types. Server test sources are intentionally out of scope — cross-module integration tests
 * (outbound flow, read listener) must reference the connector through its public classes.
 */
class ServerTelegramArchitectureTest {

    private static final List<Path> MODULE_SOURCES =
            sources(Path.of("..", "connector", "telegram", "src", "main", "java"),
                    "connector/telegram");

    private static final List<Path> SERVER_SOURCES =
            sources(Path.of("src", "main", "java"), "server");

    private static List<Path> sources(Path root, String label) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    label + " sources not found from the server module: "
                            + root.toAbsolutePath());
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test void bothSourceTreesArePresent() {
        assertFalse(MODULE_SOURCES.isEmpty(),
                "connector/telegram must contain the moved Telegram sources.");
        assertFalse(SERVER_SOURCES.isEmpty(),
                "server must contain its production sources.");
    }

    @Test void connectorModuleDoesNotDependOnServer() throws IOException {
        List<String> forbidden = List.of(
                "kit.penny.clientbus.server.");
        var violations = new ArrayList<String>();
        for (var source : MODULE_SOURCES) {
            for (var line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (!text.startsWith("import")) {
                    continue;
                }
                for (String prefix : forbidden) {
                    if (text.contains(prefix)) {
                        violations.add(source.getFileName() + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "connector/telegram imports server types: " + violations);
    }

    @Test void serverCoreDoesNotDependOnTelegram() throws IOException {
        List<String> forbidden = List.of(
                "kit.penny.tdlib",
                "org.drinkless.tdlib",
                "kit.penny.clientbus.connector.telegram");
        var violations = new ArrayList<String>();
        for (var source : SERVER_SOURCES) {
            for (var line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (!text.startsWith("import")) {
                    continue;
                }
                for (String prefix : forbidden) {
                    if (text.contains(prefix)) {
                        violations.add(source.getFileName() + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "server production code imports Telegram/TDLib types: " + violations);
    }
}
