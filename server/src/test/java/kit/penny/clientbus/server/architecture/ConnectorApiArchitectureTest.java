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
 * Stage 13.2a build rule: connector/api stays a neutral Java SPI. It may not depend on the host
 * (server, Spring, JPA, Kafka, validation, TDLib) and may import from common only enum types and
 * constants. Transport and REST DTOs from common are forbidden.
 */
class ConnectorApiArchitectureTest {

    private static final List<Path> SOURCES = sources();

    private static List<Path> sources() {
        Path root = Path.of("..", "connector", "api", "src", "main", "java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("connector/api sources not found from the server module: "
                    + root.toAbsolutePath());
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test void apiHasNoTestClasses() {
        assertFalse(SOURCES.isEmpty(), "connector/api must contain the migrated SPI sources.");
    }

    @Test void apiDoesNotDependOnTheHostOrInfrastructure() throws IOException {
        List<String> forbidden = List.of(
                "kit.penny.clientbus.server.",
                "kit.penny.clientbus.crm.",
                "org.springframework",
                "jakarta.",
                "org.apache.kafka",
                "org.hibernate",
                "tools.jackson",
                "com.fasterxml.jackson",
                "kit.penny.tdlib",
                "org.drinkless.tdlib");
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
        assertTrue(violations.isEmpty(), () -> "connector/api imports outside the SPI boundary: " + violations);
    }

    @Test void apiUsesCommonOnlyForEnumsAndConstants() throws IOException {
        List<String> allowed = List.of(
                "kit.penny.clientbus.common.enums.",
                "kit.penny.clientbus.common.notification.EventOrigin");
        var violations = new ArrayList<String>();
        for (var source : SOURCES) {
            for (var line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (!text.startsWith("import") || !text.contains("kit.penny.clientbus.common.")) {
                    continue;
                }
                if (allowed.stream().noneMatch(text::contains)) {
                    violations.add(source.getFileName() + ": " + text);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "connector/api must import only common enums/constants: " + violations);
    }
}
