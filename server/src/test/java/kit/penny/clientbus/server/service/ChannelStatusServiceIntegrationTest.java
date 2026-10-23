package kit.penny.clientbus.server.service;
import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"clientbus.notifications.creation-enabled=true","clientbus.notifications.sse-enabled=false"})
class ChannelStatusServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired ChannelStatusService statuses;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    UUID account,channel,organization;
    private <T>T save(T entity){em.persist(entity);return entity;}
    @BeforeEach void fixture(){tx.executeWithoutResult(t->{
        var org=save(TestDataFactory.organization());var w=save(TestDataFactory.workspace(org));
        var user=save(new UserEntity(UUID.randomUUID().toString(),UUID.randomUUID()+"@test.local","unused",UserRole.SUPER_ADMIN));
        save(new EmployeeEntity(org,user,"Admin","Test",null));
        var ch=save(TestDataFactory.channel(w));ch.setStatus(ChannelConnectionStatus.CONNECTED);
        var a=save(TestDataFactory.channelAccount(ch));em.flush();account=a.getId();channel=ch.getId();organization=org.getId();
    });}
    private UUID incident(){return jdbc.queryForObject("select notificationincidentid from dbo.channel where channelid=?",UUID.class,channel);}
    private int notifications(){return jdbc.queryForObject("select count(*) from dbo.notification where targetid=?",Integer.class,account);}
    private int active(){return jdbc.queryForObject("select count(*) from dbo.notification where targetid=? and alertinvalidatedat is null",Integer.class,account);}
    private String state(){return jdbc.queryForObject("select status from dbo.channel where channelid=?",String.class,channel);}
    @Test void duplicateAndConcurrentFailuresShareOnePersistentIncident() throws Exception {
        var executor=Executors.newFixedThreadPool(4);try {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->{start.await();statuses.transition(account,ChannelConnectionStatus.ERROR,"CONNECTOR_ERROR");return null;}));
            start.countDown();for(var f:futures)f.get(20,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        UUID first=incident();assertNotNull(first);assertEquals(1,notifications());
        statuses.transition(account,ChannelConnectionStatus.DISCONNECTED,"CONNECTOR_CLOSED");assertEquals(first,incident());assertEquals(1,notifications());
        statuses.beginConnecting(account);statuses.transition(account,ChannelConnectionStatus.ERROR,"RESTORE_FAILED");
        assertEquals(first,incident());assertEquals(1,notifications());assertEquals(1,active());
    }
    @Test void recoveryClosesOldAlertAndOnlyANewFailureCreatesANewOccurrence(){
        statuses.transition(account,ChannelConnectionStatus.ERROR,"FAILED");UUID first=incident();
        statuses.transition(account,ChannelConnectionStatus.CONNECTED,"READY");assertNull(incident());assertEquals(0,active());assertEquals(1,notifications());
        statuses.transition(account,ChannelConnectionStatus.DISCONNECTED,"CLOSED");assertNotEquals(first,incident());assertEquals(2,notifications());assertEquals(1,active());
    }
    @Test void explicitStopIntentSurvivesSynchronousAndLateConnectorCallbacks(){
        statuses.transition(account,ChannelConnectionStatus.ERROR,"FAILED");
        statuses.expectDisconnect(account,ChannelConnectionStatus.DISABLED);assertNull(incident());assertEquals(0,active());
        assertFalse(statuses.transition(account,ChannelConnectionStatus.DISCONNECTED,"LATE_CLOSED"));
        assertFalse(statuses.transition(account,ChannelConnectionStatus.ERROR,"LATE_ERROR"));
        assertFalse(statuses.transition(account,ChannelConnectionStatus.CONNECTED,"LATE_READY"));
        assertEquals("DISABLED",state());assertEquals(1,notifications());
        statuses.beginConnecting(account);statuses.transition(account,ChannelConnectionStatus.CONNECTED,"READY");
        statuses.transition(account,ChannelConnectionStatus.ERROR,"NEW_FAILURE");assertEquals(2,notifications());
    }
    @Test void initialAndExplicitAuthorizationDoNotAlertButUnexpectedReauthorizationDoes(){
        statuses.beginConnecting(account);statuses.transition(account,ChannelConnectionStatus.CONNECTING,"WAIT_CODE");
        statuses.transition(account,ChannelConnectionStatus.DISCONNECTED,"CLOSED_DURING_AUTH");assertNull(incident());assertEquals(0,notifications());
        statuses.beginConnecting(account);statuses.transition(account,ChannelConnectionStatus.CONNECTED,"READY");
        statuses.transition(account,ChannelConnectionStatus.CONNECTING,"WAIT_CODE");
        assertNotNull(incident());assertEquals(1,notifications());
        assertEquals("REAUTH_REQUIRED",jdbc.queryForObject("select notificationincidentreason from dbo.channel where channelid=?",String.class,channel));
    }
    @Test void recoveryAndAlertClosureRollBackTogether(){
        statuses.transition(account,ChannelConnectionStatus.ERROR,"FAILED");UUID first=incident();
        tx.executeWithoutResult(t->{statuses.transition(account,ChannelConnectionStatus.CONNECTED,"READY");t.setRollbackOnly();});
        assertEquals("ERROR",state());assertEquals(first,incident());assertEquals(1,active());
    }
    @Test void changingOneAccountLeavesAnotherAccountStateAndIncidentUntouched(){
        var other=tx.execute(t->{var ch=save(TestDataFactory.channel(em.find(ChannelEntity.class,channel).getWorkspace()));
            ch.setStatus(ChannelConnectionStatus.CONNECTED);var a=save(TestDataFactory.channelAccount(ch));em.flush();return List.of(ch.getId(),a.getId());});
        statuses.transition(account,ChannelConnectionStatus.ERROR,"FAILED");statuses.expectDisconnect(account,ChannelConnectionStatus.DISABLED);
        assertEquals("CONNECTED",jdbc.queryForObject("select status from dbo.channel where channelid=?",String.class,other.getFirst()));
        assertNull(jdbc.queryForObject("select notificationincidentid from dbo.channel where channelid=?",UUID.class,other.getFirst()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notification where targetid=?",Integer.class,other.getLast()));
    }
    @Test void missingOrRemovedAccountDoesNotChangeTheChannelOrCreateAnAlert(){
        assertFalse(statuses.transition(UUID.randomUUID(),ChannelConnectionStatus.ERROR,"UNKNOWN_ACCOUNT"));
        jdbc.update("delete from dbo.channelaccount where channelaccountid=?",account);
        assertFalse(statuses.transition(account,ChannelConnectionStatus.ERROR,"REMOVED_ACCOUNT"));
        assertEquals("CONNECTED",state());assertEquals(0,notifications());
    }
    @Test void legacyIncidentWithoutTrustedStartTimeDoesNotInventANewObservation(){
        UUID legacy=UUID.randomUUID();jdbc.update("update dbo.channel set status='ERROR',notificationincidentid=?,notificationincidentstartedat=null where channelid=?",legacy,channel);
        assertDoesNotThrow(()->statuses.transition(account,ChannelConnectionStatus.ERROR,"REPEATED_SNAPSHOT"));
        assertEquals(legacy,incident());assertEquals(0,notifications());
        statuses.transition(account,ChannelConnectionStatus.CONNECTED,"READY");assertNull(incident());
    }
    @Test void stopIntentIsCommittedBeforeTheOuterOperationCanRollBack(){
        statuses.transition(account,ChannelConnectionStatus.ERROR,"FAILED");
        tx.executeWithoutResult(t->{statuses.expectDisconnect(account,ChannelConnectionStatus.DISCONNECTED);t.setRollbackOnly();});
        assertEquals("DISCONNECTED",state());assertNull(incident());assertEquals(0,active());
    }
}
