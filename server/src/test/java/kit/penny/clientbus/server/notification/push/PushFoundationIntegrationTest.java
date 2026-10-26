package kit.penny.clientbus.server.notification.push;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.PushInstallationRequest;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.InstallationAccessPolicy;
import kit.penny.clientbus.server.notification.center.NotificationCenterService;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.notification.*;
import kit.penny.clientbus.server.security.policy.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={
    "clientbus.notifications.push.endpoint-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "clientbus.notifications.push.allowed-apps[0].app-id=clientbus.test",
    "clientbus.notifications.push.allowed-apps[0].provider=FCM",
    "clientbus.notifications.push.allowed-apps[0].environment=SANDBOX"})
class PushFoundationIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PushInstallationService registrations;
    @Autowired PushEndpointCipher cipher;
    @Autowired PushSettings settings;
    @Autowired PushIntentWriter intents;
    @Autowired PushDeliveryStore deliveries;
    @Autowired NotificationProperties config;
    @Autowired AccessPolicy access;
    @Autowired NotificationCenterService center;
    @Autowired MeterRegistry meters;
    EmployeeEntity owner,other;
    OrganizationEntity organization;
    WorkspaceEntity workspace;
    ConversationEntity conversation;
    static final String PROOF="a".repeat(43),OTHER_PROOF="b".repeat(43);
    private TransactionTemplate tx(){return new TransactionTemplate(transactions);}
    private <T>T save(T value){em.persist(value);return value;}
    private AccessSubject subject(EmployeeEntity e){return AccessSubject.recipient(e.getId());}
    @BeforeEach void fixture(){
        jdbc.update("delete from dbo.notificationdelivery");jdbc.update("delete from dbo.pushinstallation");
        tx().executeWithoutResult(status->{
            organization=save(TestDataFactory.organization());workspace=save(TestDataFactory.workspace(organization));
            var user=save(new UserEntity(UUID.randomUUID().toString(),UUID.randomUUID()+"@test.local","unused",UserRole.EMPLOYEE));
            owner=save(new EmployeeEntity(organization,user,"Owner","Test",null));
            save(new EmployeeWorkspaceEntity(owner,workspace));
            var otherOrg=save(TestDataFactory.organization());
            var otherUser=save(new UserEntity(UUID.randomUUID().toString(),UUID.randomUUID()+"@test.local","unused",UserRole.EMPLOYEE));
            other=save(new EmployeeEntity(otherOrg,otherUser,"Other","Account",null));
            var channel=save(TestDataFactory.channel(workspace));var channelAccount=save(TestDataFactory.channelAccount(channel));
            var client=save(TestDataFactory.client(organization));var account=save(TestDataFactory.clientAccount(client));
            conversation=save(TestDataFactory.conversation(workspace,channelAccount,account));conversation.setAssignedEmployee(owner);em.flush();
        });
    }
    private PushInstallationRequest request(long version,String endpoint,String proof,boolean enabled,PushPermission permission){
        return new PushInstallationRequest(version,PushPlatform.ANDROID,PushProviderType.FCM,"clientbus.test",PushEnvironment.SANDBOX,endpoint,proof,enabled,permission);
    }
    private UUID registration(){UUID id=UUID.randomUUID();registrations.put(subject(owner),id,request(0,id.toString(),PROOF,true,PushPermission.GRANTED));return id;}
    private UUID notification(){return tx().execute(status->{
        String key=UUID.randomUUID().toString();
        var occurrence=new NotificationOccurrenceEntity();occurrence.setOrganizationId(organization.getId());occurrence.setWorkspaceId(workspace.getId());
        occurrence.setOccurrenceKey(key);occurrence.setOrigin(EventOrigin.LIVE);occurrence.setRecipientEmployeeIds(new UUID[]{owner.getId()});
        occurrence.setDedupUntil(Instant.now().plus(Duration.ofDays(90)));save(occurrence);
        var dedup=new NotificationDedupEntity();dedup.setOccurrence(occurrence);dedup.setOrganizationId(organization.getId());dedup.setRecipientEmployeeId(owner.getId());
        dedup.setDeduplicationKey(key);dedup.setDedupUntil(occurrence.getDedupUntil());save(dedup);
        var n=new NotificationEntity();n.setDedup(dedup);n.setOrganizationId(organization.getId());n.setRecipientEmployeeId(owner.getId());
        n.setWorkspaceId(workspace.getId());n.setType(NotificationType.INCOMING_MESSAGE);n.setTargetKind(NotificationTargetKind.CONVERSATION);
        n.setTargetId(conversation.getId());n.setTemplateArgs(Map.of());n.setAlertExpiresAt(Instant.now().plusSeconds(300));n.setRetainUntil(Instant.now().plus(Duration.ofDays(30)));
        save(n);em.flush();return n.getId();
    });}
    private UUID deliverable(){registration();UUID id=notification();tx().executeWithoutResult(status->intents.createIntents(id));return id;}
    private String state(UUID id){return jdbc.queryForObject("select status from dbo.notificationdelivery where notificationid=?",String.class,id);}
    private double transitions(String status){return meters.counter("clientbus.notification.push.transitions","status",status).count();}
    @Test void registrationEncryptsCredentialsAndExposesOnlyVersions(){
        UUID id=registration();
        byte[] encrypted=jdbc.queryForObject("select encryptedendpoint from dbo.pushinstallation where id=?",byte[].class,id);
        assertEquals(id.toString(),cipher.decrypt(encrypted));assertFalse(Arrays.equals(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),encrypted));
        var result=registrations.put(subject(owner),id,request(1,"rotated",PROOF,true,PushPermission.GRANTED));
        assertEquals(2,result.registrationVersion());assertEquals(1,result.bindingVersion());assertEquals(2,result.endpointVersion());
        assertFalse(result.toString().contains("rotated"));assertFalse(result.toString().contains(PROOF));
    }
    @Test void proofAndCasPreventOldLogoutFromDisablingReboundAccount(){
        UUID id=registration();
        assertThrows(AccessDeniedException.class,()->registrations.put(subject(other),id,request(1,"same",OTHER_PROOF,true,PushPermission.GRANTED)));
        var rebound=registrations.put(subject(other),id,request(1,"new-owner",PROOF,true,PushPermission.GRANTED));
        assertEquals(2,rebound.bindingVersion());assertEquals(2,rebound.registrationVersion());
        assertThrows(AccessDeniedException.class,()->registrations.delete(subject(owner),id,PROOF,1));
        var conflict=assertThrows(InstallationAccessPolicy.VersionConflict.class,()->registrations.delete(subject(other),id,PROOF,1));
        assertEquals(2,conflict.currentVersion());
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,id));
    }
    @Test void activeEndpointUniquenessDoesNotTransferOwnership(){
        UUID first=registration(),second=UUID.randomUUID();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->registrations.put(subject(other),second,request(0,first.toString(),OTHER_PROOF,true,PushPermission.GRANTED)));
        assertEquals(owner.getId(),jdbc.queryForObject("select recipientemployeeid from dbo.pushinstallation where id=?",UUID.class,first));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.pushinstallation where id=?",Integer.class,second));
    }
    @Test void intentsAreTransactionalAndCreatedWhileSendingIsDisabled(){
        double queued=meters.counter("clientbus.notification.push.queued").count();
        UUID active=registration(),denied=UUID.randomUUID();
        registrations.put(subject(owner),denied,request(0,"denied",PROOF,true,PushPermission.DENIED));
        UUID n=notification();assertFalse(config.pushEnabled());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->intents.createIntents(n));
        tx().executeWithoutResult(status->{intents.createIntents(n);intents.createIntents(n);
            assertEquals(queued,meters.counter("clientbus.notification.push.queued").count(),"Uncommitted intents are not queued metrics");});
        assertEquals(queued+1,meters.counter("clientbus.notification.push.queued").count(),"Conflict duplicates are not new intents");
        assertEquals(List.of(active),jdbc.queryForList("select registrationid from dbo.notificationdelivery where notificationid=?",UUID.class,n));
        AtomicReference<UUID> rolledBack=new AtomicReference<>();
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(status->{UUID id=notification();rolledBack.set(id);intents.createIntents(id);throw new IllegalStateException("rollback");}));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notification where id=?",Integer.class,rolledBack.get()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?",Integer.class,rolledBack.get()));
        assertEquals(queued+1,meters.counter("clientbus.notification.push.queued").count(),"Rollback does not publish queue metrics");
    }
    @Test void leaseTakeoverFencesLateAcknowledgementAndInvalidEndpoint(){
        UUID n=deliverable();var old=deliveries.claim().orElseThrow();
        double accepted=transitions("provider_accepted"),failed=transitions("failed");
        jdbc.update("update dbo.notificationdelivery set leaseuntil=clock_timestamp()-interval '1 second' where id=?",old.id());
        var current=deliveries.claim().orElseThrow();assertTrue(current.claimVersion()>old.claimVersion());
        assertFalse(deliveries.terminal(old,"PROVIDER_ACCEPTED",null,"late"));assertFalse(deliveries.invalidEndpoint(old));
        assertEquals(accepted,transitions("provider_accepted"));assertEquals(failed,transitions("failed"));
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,current.installationId()));
        assertTrue(deliveries.terminal(current,"PROVIDER_ACCEPTED",null,"accepted"));assertEquals("PROVIDER_ACCEPTED",state(n));
        assertEquals(accepted+1,transitions("provider_accepted"));
    }
    @Test void errorFromOldEndpointVersionCannotDisableRotatedRegistration(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        double retry=transitions("retry"),failed=transitions("failed");
        registrations.put(subject(owner),claim.installationId(),request(1,"rotated",PROOF,true,PushPermission.GRANTED));
        assertTrue(deliveries.invalidEndpoint(claim));
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,claim.installationId()));
        assertEquals(2,jdbc.queryForObject("select registrationversion from dbo.pushinstallation where id=?",Long.class,claim.installationId()));
        assertEquals("RETRY",state(n));
        assertEquals(retry+1,transitions("retry"));assertEquals(failed,transitions("failed"));
    }
    @Test void logoutAndRebindCancelOldDeliveryWithoutChangingNotificationReadState(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        registrations.put(subject(other),claim.installationId(),request(1,"rebound",PROOF,true,PushPermission.GRANTED));
        assertEquals("CANCELLED",state(n));assertFalse(deliveries.terminal(claim,"PROVIDER_ACCEPTED",null,"late"));
        assertNull(jdbc.queryForObject("select readat from dbo.notification where id=?",java.sql.Timestamp.class,n));
    }
    @Test void retryBeyondTtlExpiresAndRetentionDoesNotUseAlertExpiry(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        double expired=transitions("expired"),retry=transitions("retry");
        assertTrue(deliveries.retry(claim,"RATE_LIMITED",Duration.ofHours(1)));assertEquals("EXPIRED",state(n));
        assertEquals(expired+1,transitions("expired"));assertEquals(retry,transitions("retry"));
        var completed=jdbc.queryForObject("select completedat from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id()).toInstant();
        var retained=jdbc.queryForObject("select retainuntil from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id()).toInstant();
        assertTrue(retained.isAfter(completed.plus(Duration.ofDays(6))));
        deliveries.cleanupBatch(Instant.now(),100);assertEquals("EXPIRED",state(n));
        deliveries.cleanupBatch(retained.plusSeconds(1),100);
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where id=?",Integer.class,claim.id()));
    }
    @Test void workerAcceptsOnlyCurrentAlertAndNeverMarksItRead(){
        UUID n=deliverable();var adapter=new TestPushProvider();
        try(var worker=worker(adapter)){
            worker.deliver(deliveries.claim().orElseThrow());assertEquals("PROVIDER_ACCEPTED",state(n));assertEquals(1,adapter.attempts());
            assertEquals(n,adapter.last().notificationId());assertTrue(adapter.last().remainingTtl().compareTo(Duration.ofMinutes(5))<=0);
            assertNull(jdbc.queryForObject("select readat from dbo.notification where id=?",java.sql.Timestamp.class,n));
        }
    }
    @Test void workerChecksCurrentAclAndReadStateBeforeProviderCall(){
        UUID n=deliverable();var adapter=new TestPushProvider();var claim=deliveries.claim().orElseThrow();
        jdbc.update("update dbo.notification set readat=clock_timestamp() where id=?",n);
        try(var worker=worker(adapter)){worker.deliver(claim);assertEquals(0,adapter.attempts());assertEquals("CANCELLED",state(n));}
        UUID next=notification();tx().executeWithoutResult(status->intents.createIntents(next));var nextClaim=deliveries.claim().orElseThrow();
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",owner.getId());
        try(var worker=worker(adapter)){worker.deliver(nextClaim);assertEquals(0,adapter.attempts());assertEquals("CANCELLED",state(next));}
    }
    @Test void deliveryIntentsSnapshotEveryActiveInstallationWithoutBackfillingNewDevices(){
        UUID first=registration(),second=registration(),n=notification();tx().executeWithoutResult(t->intents.createIntents(n));
        UUID late=registration();assertEquals(Set.of(first,second),new HashSet<>(jdbc.queryForList("select registrationid from dbo.notificationdelivery where notificationid=?",UUID.class,n)));
        UUID next=notification();tx().executeWithoutResult(t->intents.createIntents(next));
        assertEquals(Set.of(first,second,late),new HashSet<>(jdbc.queryForList("select registrationid from dbo.notificationdelivery where notificationid=?",UUID.class,next)));
    }
    @Test void endpointRotationRetriesTheSameLogicalDeliveryOnTheNewVersion(){
        UUID n=deliverable();var old=deliveries.claim().orElseThrow();var adapter=new TestPushProvider();
        registrations.put(subject(owner),old.installationId(),request(1,"rotated",PROOF,true,PushPermission.GRANTED));
        try(var worker=worker(adapter)){worker.deliver(old);assertEquals("RETRY",state(n));assertEquals(0,adapter.attempts());
            jdbc.update("update dbo.notificationdelivery set nextattemptat=clock_timestamp() where id=?",old.id());
            var current=deliveries.claim().orElseThrow();assertEquals(old.id(),current.id());assertEquals(2,current.endpointVersion());
            worker.deliver(current);assertEquals("PROVIDER_ACCEPTED",state(n));assertEquals("rotated",adapter.last().registrationReference());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?",Integer.class,n));
    }
    @Test void invalidCurrentEndpointDisablesOnlyThatInstallationAndCancelsItsOtherJobs(){
        UUID n=deliverable(),another=notification();tx().executeWithoutResult(t->intents.createIntents(another));
        var claim=deliveries.claim().orElseThrow();assertTrue(deliveries.invalidEndpoint(claim));
        assertFalse(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,claim.installationId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where registrationid=? and status='FAILED'",Integer.class,claim.installationId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where registrationid=? and status='CANCELLED'",Integer.class,claim.installationId()));
        assertNull(jdbc.queryForObject("select readat from dbo.notification where id=?",java.sql.Timestamp.class,n));
    }
    @Test void expiredTtlDoesNotCallProviderOrExtendTheDeadline(){
        UUID n=deliverable();jdbc.update("update dbo.notificationdelivery set createdat=clock_timestamp()-interval '2 seconds',expiresat=clock_timestamp()-interval '1 second' where notificationid=?",n);
        var adapter=new TestPushProvider();try(var worker=worker(adapter)){worker.deliver(deliveries.claim().orElseThrow());}
        assertEquals("EXPIRED",state(n));assertEquals(0,adapter.attempts());
        assertTrue(jdbc.queryForObject("select expiresat<clock_timestamp() from dbo.notificationdelivery where notificationid=?",Boolean.class,n));
    }
    @Test void lateAcceptedResultAfterRebindCannotOverwriteCancellation(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        double accepted=transitions("provider_accepted"),cancelled=transitions("cancelled");
        var workerMeters=new SimpleMeterRegistry();
        PushProvider adapter=new PushProvider(){public PushProviderType provider(){return PushProviderType.FCM;}
            public Result send(Request value){registrations.put(subject(other),claim.installationId(),request(1,"rebound",PROOF,true,PushPermission.GRANTED));
                return new Result(Outcome.ACCEPTED,"late",null);}};
        try(var worker=new PushDeliveryWorker(deliveries,center,access,cipher,settings,config,List.of(adapter),workerMeters)){worker.deliver(claim);}
        assertEquals("CANCELLED",state(n));assertNull(jdbc.queryForObject("select providermessageid from dbo.notificationdelivery where id=?",String.class,claim.id()));
        assertEquals(other.getId(),jdbc.queryForObject("select recipientemployeeid from dbo.pushinstallation where id=?",UUID.class,claim.installationId()));
        assertEquals(1,workerMeters.get("clientbus.notification.push.provider.responses").tag("outcome","accepted").counter().count());
        assertEquals(accepted,transitions("provider_accepted"),"Provider response cannot invent a committed delivery transition");
        assertEquals(cancelled+1,transitions("cancelled"));
    }
    @Test void serverRevokeCancelsInFlightAndCannotBeUndoneByOldProviderResult(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();registrations.delete(subject(owner),claim.installationId(),PROOF,1);
        assertEquals("CANCELLED",state(n));assertFalse(deliveries.invalidEndpoint(claim));
        assertFalse(deliveries.terminal(claim,"PROVIDER_ACCEPTED",null,"late"));
    }

    @Test void invalidOldEndpointDuringProviderCallRetriesCurrentEndpointWithoutExtendingTtl(){
        UUID n=deliverable();var original=deliveries.claim().orElseThrow();
        PushProvider adapter=new PushProvider(){public PushProviderType provider(){return PushProviderType.FCM;}
            public Result send(Request value){
                registrations.put(subject(owner),original.installationId(),request(1,"rotated-during-send",PROOF,true,PushPermission.GRANTED));
                return new Result(Outcome.INVALID_ENDPOINT,null,null);
            }};
        try(var worker=worker(adapter)){worker.deliver(original);}
        assertEquals("RETRY",state(n));
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,original.installationId()));
        jdbc.update("update dbo.notificationdelivery set nextattemptat=clock_timestamp() where id=?",original.id());
        var next=deliveries.claim().orElseThrow();assertEquals(original.id(),next.id());assertEquals(2,next.endpointVersion());
        assertEquals(original.expiresAt(),next.expiresAt());assertTrue(next.claimVersion()>original.claimVersion());
        assertFalse(deliveries.invalidEndpoint(original),"A previous claim cannot change the retry");
        var accepting=new TestPushProvider();try(var worker=worker(accepting)){worker.deliver(next);}
        assertEquals("PROVIDER_ACCEPTED",state(n));assertEquals("rotated-during-send",accepting.last().registrationReference());
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?",Integer.class,n));
    }

    @Test void invalidEndpointWaitsForLogoutWithoutDeadlock() throws Exception { concurrentRegistrationChange("logout"); }
    @Test void invalidEndpointWaitsForRebindWithoutDeadlock() throws Exception { concurrentRegistrationChange("rebind"); }
    @Test void invalidEndpointWaitsForPermissionDenialWithoutDeadlock() throws Exception { concurrentRegistrationChange("denied"); }

    @Test void invalidOldEndpointExpiresInsteadOfRetryingPastTheOriginalDeadline(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        registrations.put(subject(owner),claim.installationId(),request(1,"rotated",PROOF,true,PushPermission.GRANTED));
        jdbc.update("update dbo.notificationdelivery set createdat=clock_timestamp()-interval '2 seconds',expiresat=clock_timestamp()-interval '1 second' where id=?",claim.id());
        var expiry=jdbc.queryForObject("select expiresat from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id());
        assertTrue(deliveries.invalidEndpoint(claim));assertEquals("EXPIRED",state(n));
        assertEquals(expiry,jdbc.queryForObject("select expiresat from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id()));
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,claim.installationId()));
        assertTrue(deliveries.claim().isEmpty());
    }

    @Test void cleanupRetainsReferencedInstallationUntilDeliveryHistoryHasBeenRemoved(){
        deliverable();var claim=deliveries.claim().orElseThrow();
        assertTrue(deliveries.terminal(claim,"PROVIDER_ACCEPTED",null,"accepted"));
        var retained=jdbc.queryForObject("select retainuntil from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id()).toInstant();
        jdbc.update("update dbo.pushinstallation set lastseenat=clock_timestamp()-interval '2 days',activeuntil=clock_timestamp()-interval '1 day' where id=?",claim.installationId());
        deliveries.cleanupBatch(Instant.now(),100);
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where id=?",Integer.class,claim.id()));
        assertFalse(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?",Boolean.class,claim.installationId()));
        deliveries.cleanupBatch(retained.plusSeconds(1),100);
        deliveries.cleanupBatch(retained.plusSeconds(1),100);
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where id=?",Integer.class,claim.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.pushinstallation where id=?",Integer.class,claim.installationId()));
    }

    @Test void rolledBackDeliveryTransitionDoesNotPublishMetrics(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        double accepted=transitions("provider_accepted");
        tx().executeWithoutResult(status->{
            assertTrue(deliveries.terminal(claim,"PROVIDER_ACCEPTED",null,"not-committed"));
            assertEquals(accepted,transitions("provider_accepted"));status.setRollbackOnly();
        });
        assertEquals("IN_FLIGHT",state(n));assertEquals(accepted,transitions("provider_accepted"));
        assertTrue(deliveries.terminal(claim,"PROVIDER_ACCEPTED",null,"committed"));
        assertEquals(accepted+1,transitions("provider_accepted"));
    }

    @Test void failingObservationCannotTurnCommittedIntentIntoBusinessFailure(){
        registration();UUID committed=notification(),rolledBack=notification();
        var unavailable=mock(MeterRegistry.class);
        when(unavailable.counter("clientbus.notification.push.queued")).thenThrow(new IllegalStateException("registry unavailable"));
        var writer=new PushIntentWriter(named,new PushDeliveryMetrics(unavailable));
        assertDoesNotThrow(()->tx().executeWithoutResult(status->writer.createIntents(committed)));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?",Integer.class,committed));
        verify(unavailable,times(1)).counter("clientbus.notification.push.queued");

        tx().executeWithoutResult(status->{writer.createIntents(rolledBack);status.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?",Integer.class,rolledBack));
        verify(unavailable,times(1)).counter("clientbus.notification.push.queued");
    }

    @Test void cleanupExpiryAndRemovalMetricsFollowCommitWhilePushIsDisabled(){
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        assertFalse(config.pushEnabled());
        jdbc.update("update dbo.notificationdelivery set createdat=clock_timestamp()-interval '2 seconds',expiresat=clock_timestamp()-interval '1 second' where id=?",claim.id());
        double expired=transitions("expired");
        double cleaned=meters.counter("clientbus.notification.push.cleanup","resource","deliveries_expired").count();
        tx().executeWithoutResult(status->{deliveries.cleanupBatch(Instant.now(),100);status.setRollbackOnly();});
        assertEquals("IN_FLIGHT",state(n));assertEquals(expired,transitions("expired"));
        assertEquals(cleaned,meters.counter("clientbus.notification.push.cleanup","resource","deliveries_expired").count());
        deliveries.cleanupBatch(Instant.now(),100);
        assertEquals("EXPIRED",state(n));assertEquals(expired+1,transitions("expired"));
        assertEquals(cleaned+1,meters.counter("clientbus.notification.push.cleanup","resource","deliveries_expired").count());
        deliveries.cleanupBatch(Instant.now(),100);
        assertEquals(expired+1,transitions("expired"),"Repeated sweep does not count an already terminal delivery");
        var retained=jdbc.queryForObject("select retainuntil from dbo.notificationdelivery where id=?",java.sql.Timestamp.class,claim.id()).toInstant();
        double removed=meters.counter("clientbus.notification.push.cleanup","resource","deliveries_removed").count();
        deliveries.cleanupBatch(retained.plusSeconds(1),100);
        assertEquals(removed+1,meters.counter("clientbus.notification.push.cleanup","resource","deliveries_removed").count());
    }

    private void concurrentRegistrationChange(String change) throws Exception {
        UUID n=deliverable();var claim=deliveries.claim().orElseThrow();
        var started=new java.util.concurrent.CompletableFuture<Integer>();
        var result=new AtomicReference<java.util.concurrent.Future<Boolean>>();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            tx().executeWithoutResult(status->{
                // Pause a real mutation after its first lock. The result handler must wait here BEFORE locking delivery.
                jdbc.queryForObject("select id from dbo.pushinstallation where id=? for update",UUID.class,claim.installationId());
                int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                result.set(executor.submit(()->tx().execute(s->{
                    started.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    return deliveries.invalidEndpoint(claim);
                })));
                int waiter=started.orTimeout(5,java.util.concurrent.TimeUnit.SECONDS).join();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(!Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,holder,waiter))){
                    if(System.nanoTime()>deadline)fail("Provider result did not wait for the installation lock");
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
                }
                switch(change){
                    case "logout"->registrations.delete(subject(owner),claim.installationId(),PROOF,1);
                    case "rebind"->registrations.put(subject(other),claim.installationId(),request(1,"rebound",PROOF,true,PushPermission.GRANTED));
                    case "denied"->registrations.put(subject(owner),claim.installationId(),request(1,"denied",PROOF,true,PushPermission.DENIED));
                    default->throw new IllegalArgumentException(change);
                }
            });
            assertFalse(result.get().get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED",state(n));
            assertEquals(2,jdbc.queryForObject("select registrationversion from dbo.pushinstallation where id=?",Long.class,claim.installationId()));
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    private PushDeliveryWorker worker(PushProvider provider){return new PushDeliveryWorker(deliveries,center,access,cipher,settings,config,List.of(provider),new SimpleMeterRegistry());}
}
