package kit.penny.clientbus.server.notification;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.notification.transport.UiSseGateway;
import kit.penny.clientbus.server.notification.outbox.KafkaUiOutboxPublisher;
import kit.penny.clientbus.server.notification.outbox.UiOutboxPublisher;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in acceptance; no test HTTP controls are registered in the application. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "clientbus.notifications.sse-enabled=true","clientbus.notifications.heartbeat-seconds=1"})
@ActiveProfiles({"test","notification-browser"})
@Import({NotificationBrowserAcceptance.Control.class,NotificationBrowserAcceptance.GatedPublisher.class})
class NotificationBrowserAcceptance extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwords;
    @Autowired Control control;
    private <T> T save(T value){em.persist(value);return value;}

    @Test void browserVertical() throws Exception {
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(project.getFileName().toString().equals("server"))project=project.getParent();
        Path manifest=project.resolve("server/target/notification-browser.json"),log=project.resolve("server/target/notification-browser.log");
        String password=UUID.randomUUID().toString(),hash=passwords.encode(password);
        Map<String,Object> fixture=tx.execute(status->{
            var org=save(TestDataFactory.organization());var w=save(TestDataFactory.workspace(org,"Notification acceptance"));
            var result=new LinkedHashMap<String,Object>();result.put("workspaceId",w.getId());
            EmployeeEntity first=null;
            for(String role:List.of("allowed","forbidden")) {
                String username="notification-"+UUID.randomUUID();
                var u=save(new UserEntity(username,username+"@example.test",hash,UserRole.EMPLOYEE));
                var e=save(new EmployeeEntity(org,u,role,"Acceptance",null));save(new EmployeeWorkspaceEntity(e,w));
                result.put(role,Map.of("username",username,"password",password));
                if(role.equals("allowed"))first=e;
            }
            var channel=save(TestDataFactory.channelAccount(save(TestDataFactory.channel(w))));
            var account=save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            account.setDisplayName("Notification contact");
            var c=save(TestDataFactory.conversation(w,channel,account));c.setAssignedEmployee(first);
            c.setHistoryStartReached(true);c.setLastMessageAt(Instant.now());c.setLastMessagePreview("Initial notification message");
            var m=new MessageEntity(c,MessageType.TEXT,MessageDirection.INBOUND,MessageSenderType.CLIENT);
            m.setClientAccount(account);m.setContent("Initial notification message");m.setExternalId("initial");
            m.setSentAt(Instant.now());m.setProcessingStatus(MessageProcessingStatus.PROCESSED);save(m);
            result.put("conversationId",c.getId());
            control.conversation=c.getId();control.channel=channel.getId();control.externalClient=account.getExternalId();
            return result;
        });
        Files.writeString(manifest,new JsonMapper().writeValueAsString(Map.of("baseURL","http://127.0.0.1:"+port,"fixture",fixture)));
        var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test","--config","playwright.notifications.config.ts");
        builder.directory(project.resolve("ui").toFile());builder.environment().put("PW_NOTIFICATION_MANIFEST",manifest.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        var browser=builder.start();
        try {
            assertTrue(browser.waitFor(10,TimeUnit.MINUTES),"Notification browser acceptance timed out; see "+log);
            assertEquals(0,browser.exitValue(),"Notification browser acceptance failed; see "+log);
        } finally {browser.descendants().forEach(ProcessHandle::destroy);browser.destroy();Files.deleteIfExists(manifest);}
    }

    @TestComponent
    @Profile("notification-browser")
    @Primary
    static class GatedPublisher implements UiOutboxPublisher {
        @Autowired KafkaUiOutboxPublisher delegate;
        volatile boolean paused;
        public void publish(String key,String payload) throws Exception {
            if(paused) throw new java.io.IOException("Acceptance relay paused");
            delegate.publish(key,payload);
        }
    }

    @TestComponent
    @Profile("notification-browser")
    @RestController
    @RequestMapping("/api/test/notifications")
    static class Control {
        @Autowired MessageService messages;
        @Autowired IPlatformMessagePublisher publisher;
        @Autowired TransactionTemplate tx;
        @Autowired JdbcTemplate jdbc;
        @Autowired UiSseGateway gateway;
        @Autowired GatedPublisher publisherGate;
        volatile UUID conversation,channel;
        volatile String externalClient;
        record Input(String externalId,String content,Boolean rollback) {}
        @PostMapping("/message") Map<String,Object> message(@RequestBody Input input) {
            if(Boolean.TRUE.equals(input.rollback())) tx.executeWithoutResult(status->{
                messages.createPlatformMessage(new CreatePlatformMessageRequest(conversation,MessageType.TEXT,input.externalId(),input.content(),null,Instant.now(),false),EventOrigin.LIVE);
                status.setRollbackOnly();
            });
            else publisher.publish(new PlatformMessageRequest(channel,externalClient,null,null,"Notification contact",externalClient,
                    input.externalId(),MessageType.TEXT,input.content(),null,Instant.now(),List.of(),EventOrigin.LIVE));
            return Map.of("accepted",true);
        }
        @PostMapping("/disconnect") Map<String,Object> disconnect(){gateway.pipelineLost();return Map.of("accepted",true);}
        record RelayInput(boolean paused) {}
        @PostMapping("/relay") Map<String,Object> relay(@RequestBody RelayInput input) {
            publisherGate.paused=input.paused();
            if(!input.paused()) jdbc.update("update dbo.notification_outbox set next_attempt_at=clock_timestamp() where status='RETRY'");
            return Map.of("accepted",true);
        }
        @GetMapping("/stats") Map<String,Object> stats(){return Map.of(
                "messages",jdbc.queryForObject("select count(*) from dbo.message where conversationid=?",Integer.class,conversation),
                "eventIds",jdbc.queryForList("select event_id from dbo.notification_outbox where payload->'resource'->>'id'=? order by event_id",UUID.class,conversation.toString()),
                "published",jdbc.queryForObject("select count(*) from dbo.notification_outbox where payload->'resource'->>'id'=? and status='PUBLISHED'",Integer.class,conversation.toString()),
                "pending",jdbc.queryForObject("select count(*) from dbo.notification_outbox where payload->'resource'->>'id'=? and status in ('PENDING','RETRY','IN_FLIGHT')",Integer.class,conversation.toString()));}
    }
}
