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
 * Stage 13.4 build rule: connector/dikidi talks to DIKIDI only through
 * integration/dikidi and the connector SPI. It may not import the CRM domain
 * contract (crm/api), the CRM adapter (crm/dikidi), the host (server) or the
 * Telegram connector — adapters never depend on each other.
 */
class DikidiConnectorArchitectureTest {

    private static final List<Path> SOURCES = sources();

    private static List<Path> sources() {
        Path root = Path.of("..", "connector", "dikidi", "src", "main", "java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("connector/dikidi sources not found from the server module: "
                    + root.toAbsolutePath());
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test void moduleHasSources() {
        assertFalse(SOURCES.isEmpty(), "connector/dikidi must contain the chat channel sources.");
    }

    @Test void adaptersNeverDependOnCrmOrEachOther() throws IOException {
        List<String> forbidden = List.of(
                "kit.penny.clientbus.crm.",
                "kit.penny.clientbus.server.",
                "kit.penny.clientbus.connector.telegram.",
                "kit.penny.tdlib",
                "jakarta.persistence",
                "org.apache.kafka",
                "org.hibernate");
        var violations = new ArrayList<String>();
        for (var source : SOURCES) {
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
        assertTrue(violations.isEmpty(), () -> "connector/dikidi imports outside the channel boundary: " + violations);
    }
}
