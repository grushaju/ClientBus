package kit.penny.clientbus.server.notification.acceptance;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.notification.transport.UiSseGateway;
import kit.penny.clientbus.server.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit local profile, never a production benchmark or part of the default unit suite. */
class NotificationLoadBrowserAcceptance {
    @Test void measuredLocalProfile() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("server")) root = root.getParent();
        Path manifest = root.resolve("server/target/notification-load-browser.json");
        Path log = root.resolve("server/target/notification-load-browser.log");
        Process browser = null;
        try (var environment = NotificationAcceptanceEnvironment.start(ProfileConfiguration.class)) {
            var fixture = environment.fixture("load");
            seed(environment, fixture);
            Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of(
                    "baseURLs", List.of(environment.baseUrl(0), environment.baseUrl(1)), "fixture", fixture,
                    "host", Map.of("os", System.getProperty("os.name"), "java", System.getProperty("java.version"),
                            "processors", Runtime.getRuntime().availableProcessors(), "maxHeapBytes", Runtime.getRuntime().maxMemory()),
                    "profile", Map.of("contexts", 8, "dialogs", 20, "notifications", 1000, "publishedOutbox", 20000,
                            "normalMs", 300000, "spacingMs", 500, "burst", 100, "outageMs", 30000))));
            var command = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                    "--config", "playwright.notification-load.config.ts");
            command.directory(root.resolve("ui").toFile());
            command.environment().put("PW_NOTIFICATION_LOAD_MANIFEST", manifest.toString());
            command.redirectErrorStream(true).redirectOutput(log.toFile());
            browser = command.start();
            assertTrue(browser.waitFor(15, TimeUnit.MINUTES), "Local notification profile timed out; see " + log);
            assertEquals(0, browser.exitValue(), "Local notification profile failed; see " + log);
        } finally {
            if (browser != null) { browser.descendants().forEach(ProcessHandle::destroy); browser.destroy(); }
            Files.deleteIfExists(manifest);
        }
    }

    private static void seed(NotificationAcceptanceEnvironment environment, Map<String, Object> fixture) {
        UUID organization = (UUID) fixture.get("organizationId"), workspace = (UUID) fixture.get("workspaceId");
        UUID recipient = (UUID) fixture.get("ownerId"), conversation = (UUID) fixture.get("conversationId");
        environment.inTransaction(em -> {
            var org = em.find(OrganizationEntity.class, organization);
            var ws = em.find(WorkspaceEntity.class, workspace);
            var channel = em.find(ChannelAccountEntity.class, fixture.get("channelAccountId"));
            for (int index = 0; index < 18; index++) {
                var client = TestDataFactory.client(org); em.persist(client);
                var account = TestDataFactory.clientAccount(client); account.setChannelType(channel.getChannel().getType()); em.persist(account);
                var extra = TestDataFactory.conversation(ws, channel, account);
                extra.setAssignedEmployee(em.getReference(EmployeeEntity.class, recipient)); em.persist(extra);
            }
            return null;
        });
        var jdbc = environment.context(0).getBean(JdbcTemplate.class);
        // One atomic seed chain obeys the same FK/dedup/retention constraints as live notifications.
        jdbc.update("""
                with occurrences as (
                  insert into dbo.notificationoccurrence
                    (id,organizationid,workspaceid,occurrencekey,origin,recipientemployeeids,dedupuntil)
                  select gen_random_uuid(),?,?, 'load-seed-'||i,'LIVE',array[?]::uuid[],clock_timestamp()+interval '90 days'
                  from generate_series(1,1000) i returning id,organizationid,occurrencekey,dedupuntil
                ), dedups as (
                  insert into dbo.notificationdedup
                    (id,occurrenceid,organizationid,recipientemployeeid,deduplicationkey,dedupuntil)
                  select gen_random_uuid(),id,organizationid,?,occurrencekey,dedupuntil from occurrences
                  returning id,organizationid,recipientemployeeid
                ) insert into dbo.notification
                    (id,dedupid,organizationid,recipientemployeeid,workspaceid,type,targetkind,targetid,templateargs,alertexpiresat,retainuntil)
                  select gen_random_uuid(),id,organizationid,recipientemployeeid,?,'INCOMING_MESSAGE','CONVERSATION',?,
                    '{}'::jsonb,clock_timestamp()+interval '5 minutes',clock_timestamp()+interval '30 days' from dedups
                """, organization, workspace, recipient, recipient, workspace, conversation);
        jdbc.update("""
                insert into dbo.notificationoutbox
                  (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,nextattemptat,publishedat)
                select gen_random_uuid(),gen_random_uuid(),?,'clientbus.ui.invalidations.v1','LOAD_ARCHIVE',1,'{}'::jsonb,
                  'PUBLISHED',1,1,clock_timestamp(),clock_timestamp() from generate_series(1,20000)
                """, organization);
        jdbc.execute("analyze dbo.notificationoutbox");
        assertEquals(20, jdbc.queryForObject("select count(*) from dbo.conversation where workspaceid=?", Integer.class, workspace));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("notification-stage-acceptance")
    @Import(ProfileControl.class)
    public static class ProfileConfiguration {}

    @RestController
    @Profile("notification-stage-acceptance")
    @RequestMapping("/api/test/notification-load")
    public static class ProfileControl {
        @Autowired NotificationAcceptanceEnvironment environment;
        @GetMapping("/stats") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> stats() {
            var fixture = environment.fixture("load");
            var jdbc = environment.context(0).getBean(JdbcTemplate.class);
            var result = new LinkedHashMap<String, Object>();
            result.put("sampledAt", Instant.now());
            result.put("outboxPending", jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and status in ('PENDING','RETRY','IN_FLIGHT')", Long.class, fixture.get("organizationId")));
            result.put("outboxQuarantined", jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and status='QUARANTINED'", Long.class, fixture.get("organizationId")));
            result.put("outboxOldestSeconds", jdbc.queryForObject("select coalesce(extract(epoch from clock_timestamp()-min(createdat)),0) from dbo.notificationoutbox where organizationid=? and status in ('PENDING','RETRY','IN_FLIGHT')", Double.class, fixture.get("organizationId")));
            result.put("nodes", java.util.stream.IntStream.range(0, 2).mapToObj(index -> {
                var context = environment.context(index); var meters = context.getBean(MeterRegistry.class);
                var node = new LinkedHashMap<String, Object>();
                node.put("node", index); node.put("gatewayReady", context.getBean(UiSseGateway.class).ready());
                for (String name : List.of("clientbus.notification.sse.connections", "clientbus.notification.sse.connections.capacity",
                        "clientbus.notification.sse.queue.entries", "clientbus.notification.sse.queue.capacity",
                        "clientbus.notification.sse.workers.active", "clientbus.notification.sse.workers.capacity",
                        "clientbus.notification.sse.executor.queue.entries", "clientbus.notification.sse.executor.queue.capacity",
                        "clientbus.notification.kafka.lag.records", "hikaricp.connections.active", "hikaricp.connections.max")) {
                    var values = meters.find(name).gauges().stream().mapToDouble(g -> g.value()).filter(Double::isFinite).toArray();
                    if (values.length > 0) node.put(name, Arrays.stream(values).sum());
                }
                return node;
            }).toList());
            // Both isolated contexts run in one JVM: these are process-wide, not per-node totals.
            result.put("process", Map.of("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    "liveThreads", ManagementFactory.getThreadMXBean().getThreadCount()));
            return result;
        }
    }
}
