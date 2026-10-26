package kit.penny.clientbus.server.notification.acceptance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real browsers against two complete isolated backend instances. */
class NotificationStageBrowserAcceptance {
    @Test void twoBackendsDesktopAndMobile() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (project.getFileName().toString().equals("server")) project = project.getParent();
        try (var bundled = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull(bundled, "Build UI and copy resources before acceptance");
            assertArrayEquals(Files.readAllBytes(project.resolve("ui/dist/index.html")), bundled.readAllBytes(),
                    "Refresh classpath UI resources before acceptance");
        }
        Path manifest = project.resolve("server/target/notification-stage-browser.json");
        Path log = project.resolve("server/target/notification-stage-browser.log");
        Process browser = null;
        try (var environment = NotificationAcceptanceEnvironment.start()) {
            var fixtures = new LinkedHashMap<String, Object>();
            for (String device : List.of("desktop", "mobile"))
                for (String flow : List.of("cross", "outage", "history", "sound", "metadata", "directories"))
                    fixtures.put(device + "-" + flow, environment.fixture(device + "-" + flow));
            Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of(
                    "baseURLs", List.of(environment.baseUrl(0), environment.baseUrl(1)), "fixtures", fixtures)));
            var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                    "--config", "playwright.notification-acceptance.config.ts");
            if (System.getProperty("acceptance.grep") != null)
                builder.command().addAll(List.of("--grep", System.getProperty("acceptance.grep")));
            builder.directory(project.resolve("ui").toFile());
            builder.environment().put("PW_NOTIFICATION_ACCEPTANCE_MANIFEST", manifest.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            browser = builder.start();
            assertTrue(browser.waitFor(15, TimeUnit.MINUTES), "Stage browser acceptance timed out; see " + log);
            assertEquals(0, browser.exitValue(), "Stage browser acceptance failed; see " + log);
        } finally {
            if (browser != null) { browser.descendants().forEach(ProcessHandle::destroy); browser.destroy(); }
            Files.deleteIfExists(manifest);
        }
    }
}
