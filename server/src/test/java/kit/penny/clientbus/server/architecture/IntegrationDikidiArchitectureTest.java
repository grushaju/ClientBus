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
 * Stage 13.4 build rule: integration/dikidi is the technical DIKIDI module. It owns transport,
 * login/session pool and the request regulator, and may not know about the CRM domain contract
 * (crm/api), the host (server, Spring, JPA, Kafka) or connector SPI. Jackson is allowed only for
 * the login handshake payload.
 */
class IntegrationDikidiArchitectureTest {

    private static final List<Path> SOURCES = sources();

    private static List<Path> sources() {
        Path root = Path.of("..", "integration", "dikidi", "src", "main", "java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("integration/dikidi sources not found from the server module: "
                    + root.toAbsolutePath());
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test void moduleHasSources() {
        assertFalse(SOURCES.isEmpty(), "integration/dikidi must contain the migrated transport sources.");
    }

    @Test void moduleDoesNotDependOnCrmDomainOrHost() throws IOException {
        List<String> forbidden = List.of(
                "kit.penny.clientbus.crm.",
                "kit.penny.clientbus.server.",
                "kit.penny.clientbus.connector.",
                "kit.penny.clientbus.common.",
                "org.springframework",
                "jakarta.",
                "org.apache.kafka",
                "org.hibernate",
                "com.fasterxml.jackson");
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
        assertTrue(violations.isEmpty(), () -> "integration/dikidi imports outside the technical boundary: " + violations);
    }
}
