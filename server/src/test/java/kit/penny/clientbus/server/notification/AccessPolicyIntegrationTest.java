package kit.penny.clientbus.server.notification;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.security.policy.*;
import kit.penny.clientbus.server.service.OrganizationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.*;

@SpringBootTest
@Transactional
class AccessPolicyIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessPolicy policy;
    @Autowired NotificationRoutingPolicy routing;
    @Autowired InstallationAccessPolicy installations;
    @Autowired OrganizationService organizations;
    @Autowired NotificationAudienceResolver audiences;
    @Autowired kit.penny.clientbus.server.security.service.CurrentUserService currentUser;
    OrganizationEntity org, otherOrg;
    WorkspaceEntity workspace, otherWorkspace;
    EmployeeEntity owner, other, supervisor, admin, outsider;
    ChannelAccountEntity channel;
    ConversationEntity conversation;

    private <T> T save(T value) { em.persist(value); return value; }
    private EmployeeEntity employee(OrganizationEntity organization, UserRole role, boolean member, boolean manager) {
        String id = UUID.randomUUID().toString();
        UserEntity user = save(new UserEntity(id, id+"@test.local", "unused", role));
        EmployeeEntity e = save(new EmployeeEntity(organization, user, "First", "Last", null));
        if (member) {
            EmployeeWorkspaceEntity m = new EmployeeWorkspaceEntity(e, workspace);
            m.setSupervisor(manager); save(m);
        }
        return e;
    }
    private AccessSubject subject(EmployeeEntity e) { return AccessSubject.recipient(e.getId()); }
    @BeforeEach void data() {
        org=save(TestDataFactory.organization()); otherOrg=save(TestDataFactory.organization());
        workspace=save(TestDataFactory.workspace(org)); otherWorkspace=save(TestDataFactory.workspace(otherOrg));
        owner=employee(org,UserRole.EMPLOYEE,true,false);
        other=employee(org,UserRole.EMPLOYEE,true,false);
        supervisor=employee(org,UserRole.EMPLOYEE,true,true);
        admin=employee(org,UserRole.SUPER_ADMIN,false,false);
        outsider=employee(otherOrg,UserRole.SUPER_ADMIN,false,false);
        ChannelEntity ch=save(TestDataFactory.channel(workspace));
        channel=save(TestDataFactory.channelAccount(ch));
        ClientEntity client=save(TestDataFactory.client(org));
        ClientAccountEntity account=save(TestDataFactory.clientAccount(client));
        conversation=save(TestDataFactory.conversation(workspace,channel,account));
        conversation.setAssignedEmployee(owner); em.flush();
    }
    @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }

    @Test void conversationMatrixAndBatchHaveIdenticalAcl() {
        var candidates=List.of(owner.getId(),other.getId(),supervisor.getId(),admin.getId(),outsider.getId());
        assertEquals(Set.of(owner.getId(),supervisor.getId(),admin.getId()),policy.allowedRecipients(candidates,CONVERSATION,conversation.getId()));
        for (var e:List.of(owner,other,supervisor,admin,outsider))
            assertEquals(policy.allowedRecipients(candidates,CONVERSATION,conversation.getId()).contains(e.getId()),policy.allows(subject(e),CONVERSATION,conversation.getId()));
        jdbc.update("update dbo.conversation set assignedemployeeid=null where conversationid=?",conversation.getId());
        assertTrue(policy.allows(subject(other),CONVERSATION,conversation.getId()));
        assertFalse(policy.allows(subject(admin),WORKSPACE,otherWorkspace.getId()));
    }

    @Test void revocationAndReassignmentIgnoreManagedEntityAndPrincipalCaches() {
        assertTrue(policy.allows(subject(owner),CONVERSATION,conversation.getId()));
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?",other.getId(),conversation.getId());
        assertEquals(owner,conversation.getAssignedEmployee()); // intentionally stale managed entity
        assertFalse(policy.allows(subject(owner),CONVERSATION,conversation.getId()));
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",other.getId());
        assertFalse(policy.allows(subject(other),WORKSPACE,workspace.getId()));
        jdbc.update("update dbo.users set isenabled=false where userid=?",admin.getUser().getId());
        assertTrue(admin.getUser().isEnabled());
        assertFalse(policy.allows(subject(admin),WORKSPACE,workspace.getId()));
    }

    @Test void httpConversationGuardUsesDatabaseInsteadOfCachedAssignment() {
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?",other.getId(),conversation.getId());
        assertEquals(owner,conversation.getAssignedEmployee());
        var principal=new UserPrincipal(other.getUser());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        assertDoesNotThrow(()->currentUser.requireConversationAccess(conversation));
        principal=new UserPrincipal(owner.getUser());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        assertThrows(AccessDeniedException.class,()->currentUser.requireConversationAccess(conversation));
    }

    @Test void channelManagementIsSameOrganizationAdminOnly() {
        assertTrue(policy.allows(subject(owner),CHANNEL_ACCOUNT,channel.getId()));
        assertFalse(policy.allows(subject(owner),CHANNEL_ACCOUNT_MANAGEMENT,channel.getId()));
        assertFalse(policy.allows(subject(supervisor),CHANNEL_ACCOUNT_MANAGEMENT,channel.getId()));
        assertTrue(policy.allows(subject(admin),CHANNEL_ACCOUNT_MANAGEMENT,channel.getId()));
        assertFalse(policy.allows(subject(outsider),CHANNEL_ACCOUNT_MANAGEMENT,channel.getId()));
        jdbc.update("update dbo.users set role='EMPLOYEE' where userid=?",admin.getUser().getId());
        assertFalse(policy.allows(subject(admin),CHANNEL_ACCOUNT_MANAGEMENT,channel.getId()));
    }

    @Test void organizationReadChecksRequestedId() {
        var principal=new UserPrincipal(owner.getUser());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        assertNotNull(organizations.getOrganization(org.getId()));
        assertThrows(AccessDeniedException.class,()->organizations.getOrganization(otherOrg.getId()));
    }

    @Test void expiredSessionDeniedEvenForAdmin() {
        assertFalse(policy.allows(AccessSubject.session(admin.getId(),Instant.now().minusSeconds(1)),WORKSPACE,workspace.getId()));
        assertTrue(policy.allows(AccessSubject.session(admin.getId(),Instant.now().plusSeconds(60)),WORKSPACE,workspace.getId()));
    }

    @Test void initialAudiencesRespectAssignmentSelfAndFallback() {
        assertEquals(Set.of(owner.getId()),audiences.incoming(conversation.getId()));
        assertTrue(audiences.assignment(conversation.getId(),owner.getId()).isEmpty());
        assertEquals(Set.of(owner.getId()),audiences.assignment(conversation.getId(),admin.getId()));
        assertEquals(Set.of(owner.getId()),audiences.outboundFailure(conversation.getId()));
        jdbc.update("update dbo.users set isenabled=false where userid=?",owner.getUser().getId());
        assertTrue(audiences.incoming(conversation.getId()).isEmpty());
        assertEquals(Set.of(admin.getId(),supervisor.getId()),audiences.outboundFailure(conversation.getId()));
        assertEquals(Set.of(admin.getId()),audiences.channelIncident(channel.getId()));
    }

    private UUID notification() {
        UUID occurrence=UUID.randomUUID(), dedup=UUID.randomUUID(), id=UUID.randomUUID();
        jdbc.update("insert into dbo.notificationoccurrence (id,organizationid,occurrencekey,origin,recipientemployeeids,dedupuntil) values (?,?,'key','LIVE',ARRAY[?]::uuid[],clock_timestamp()+interval '90 days')",occurrence,org.getId(),owner.getId());
        jdbc.update("insert into dbo.notificationdedup (id,occurrenceid,organizationid,recipientemployeeid,deduplicationkey,dedupuntil) values (?,?,?,?,'key',clock_timestamp()+interval '90 days')",dedup,occurrence,org.getId(),owner.getId());
        jdbc.update("insert into dbo.notification (id,dedupid,organizationid,recipientemployeeid,workspaceid,type,targetkind,targetid,templateargs,alertexpiresat,retainuntil) values (?,?,?,?,?,'INCOMING_MESSAGE','CONVERSATION',?,'{}',clock_timestamp()+interval '5 minutes',clock_timestamp()+interval '30 days')",id,dedup,org.getId(),owner.getId(),workspace.getId(),conversation.getId());
        return id;
    }
    private UUID operation() {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into dbo.historysyncoperation (id,organizationid,workspaceid,conversationid,channelaccountid,
                initiatoremployeeid,idempotencykey,requesthash,requestlimit,status,attempts,claimversion,deadlineat,updatedat,retainuntil)
                values (?,?,?,?,?,?,'request','hash',50,'PENDING',0,0,clock_timestamp()+interval '2 minutes',clock_timestamp(),clock_timestamp()+interval '7 days')
                """,id,org.getId(),workspace.getId(),conversation.getId(),channel.getId(),owner.getId());
        return id;
    }
    @Test void notificationAndOperationRemainPersonalAndRequireCurrentTargetAccess() {
        UUID id=notification(), operation=operation();
        assertTrue(policy.allows(subject(owner),NOTIFICATION,id));
        assertTrue(policy.allows(subject(owner),HISTORY_OPERATION,operation));
        assertFalse(policy.allows(subject(admin),NOTIFICATION,id));
        assertFalse(policy.allows(subject(admin),HISTORY_OPERATION,operation));
        assertEquals(Set.of(owner.getId()),audiences.operation(operation));
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?",other.getId(),conversation.getId());
        assertFalse(policy.allows(subject(owner),NOTIFICATION,id));
        assertFalse(policy.allows(subject(owner),HISTORY_OPERATION,operation));
        assertFalse(policy.allows(subject(owner),NOTIFICATION,UUID.randomUUID()));
    }

    @Test void pushAuthorizationRejectsReadInvalidatedAndStaleInstallation() {
        UUID id=notification(), installation=UUID.randomUUID(), delivery=UUID.randomUUID();
        jdbc.update("""
                insert into dbo.pushinstallation (id,organizationid,recipientemployeeid,platform,provider,appid,environment,
                encryptedendpoint,endpointfingerprint,activeendpointfingerprint,ownershipsecrethash,bindingversion,endpointversion,
                enabled,reportedpermission,lastseenat,activeuntil) values (?,?,?,'IOS','APNS','test','SANDBOX',?, ?, ?, ?,1,1,true,'GRANTED',clock_timestamp(),clock_timestamp()+interval '1 day')
                """,installation,org.getId(),owner.getId(),new byte[]{1},"b".repeat(64),"b".repeat(64),new byte[32]);
        jdbc.update("""
                insert into dbo.notificationdelivery (id,notificationid,registrationid,bindingversion,channel,status,attempts,
                claimversion,attemptendpointversion,leaseuntil,nextattemptat,expiresat)
                values (?,?,?,1,'MOBILE_PUSH','IN_FLIGHT',1,1,1,clock_timestamp()+interval '30 seconds',clock_timestamp(),clock_timestamp()+interval '5 minutes')
                """,delivery,id,installation);
        assertTrue(policy.allowsPush(delivery));
        jdbc.update("update dbo.pushinstallation set endpointversion=2,registrationversion=2 where id=?",installation);
        assertFalse(policy.allowsPush(delivery));
        jdbc.update("update dbo.notificationdelivery set attemptendpointversion=2 where id=?",delivery);
        assertTrue(policy.allowsPush(delivery));
        jdbc.update("update dbo.notification set alertinvalidatedat=clock_timestamp() where id=?",id);
        assertFalse(policy.allowsPush(delivery));
        assertTrue(policy.allows(subject(owner),NOTIFICATION,id));
    }

    @Test void privateResourcesNeverBecomeAdminReadable() {
        var group=save(new ConversationGroupEntity(workspace,owner,"private"));
        var filter=new SavedConversationFilterEntity();
        filter.setWorkspace(workspace); filter.setOwnerEmployee(owner); filter.setName("private"); filter.setFilterDefinition("{}"); save(filter); em.flush();
        assertTrue(policy.allows(subject(owner),PERSONAL_GROUP,group.getId()));
        assertFalse(policy.allows(subject(admin),PERSONAL_GROUP,group.getId()));
        assertTrue(policy.allows(subject(owner),SAVED_FILTER,filter.getId()));
        assertFalse(policy.allows(subject(admin),SAVED_FILTER,filter.getId()));
        assertTrue(policy.allows(subject(supervisor),EMPLOYEE,owner.getId()));
        assertFalse(policy.allows(subject(other),EMPLOYEE,owner.getId()));
    }

    private UiInvalidation event(UiInvalidation.Audience audience, String kind, UUID id) {
        return new UiInvalidation(UUID.randomUUID(),1,"inbox.invalidated",Instant.now(),org.getId(),workspace.getId(),
                new UiResourceRef(kind,id),audience,Set.of(owner.getId()),EventOrigin.LIVE,null,UUID.randomUUID());
    }
    @Test void formerScopeOnlyEmitsCleanupAfterDeletionOrMembershipLoss() {
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",owner.getId());
        var event=event(UiInvalidation.Audience.FORMER_SCOPE,"conversation",UUID.randomUUID());
        var output=routing.forRecipient(subject(owner),event).orElseThrow();
        assertEquals("access.invalidated",output.type()); assertNull(output.resource());
        assertNull(output.workspaceId()); assertNull(output.correlationId());
        assertTrue(routing.forRecipient(subject(other),event).isEmpty());
        assertTrue(routing.forRecipient(subject(outsider),event).isEmpty());
    }

    @Test void broadEventsDoNotExposeConversationIdentifiers() {
        var event=event(UiInvalidation.Audience.WORKSPACE,"conversation",conversation.getId());
        assertNull(routing.forRecipient(subject(other),event).orElseThrow().resource());
        assertTrue(routing.forRecipient(subject(other),event(UiInvalidation.Audience.CONVERSATION,"conversation",conversation.getId())).isEmpty());
        assertNotNull(routing.forRecipient(subject(owner),event(UiInvalidation.Audience.CONVERSATION,"conversation",conversation.getId())).orElseThrow().resource());
    }

    @Test void knownScopeMetadataKeepsItsTypeWithoutAnyResourceOrPrivateScopeData() {
        for (var audience : List.of(UiInvalidation.Audience.WORKSPACE, UiInvalidation.Audience.ORGANIZATION)) {
            for (String type : List.of("clients.invalidated", "channels.invalidated", "inbox.invalidated", "filters.invalidated", "groups.invalidated")) {
                var source = event(audience, "conversation", conversation.getId());
                var hint = new UiInvalidation(source.eventId(), 1, type, source.occurredAt(), source.organizationId(),
                        source.workspaceId(), source.resource(), audience, source.recipientEmployeeIds(),
                        source.origin(), source.actorEmployeeId(), source.correlationId());
                var output = routing.forRecipient(subject(other), hint).orElseThrow();
                assertEquals(type, output.type()); assertNull(output.resource()); assertNull(output.workspaceId());
                assertNull(output.correlationId()); assertNull(output.stream());
                assertTrue(routing.forRecipient(subject(outsider), hint).isEmpty());
            }
        }
    }

    @Test void unknownTypeOrSchemaResyncsOnlyAuthorizedAudienceWithoutResourceData() {
        for (var audience:UiInvalidation.Audience.values()) {
            for (int schema:List.of(1,2)) {
                var source=event(audience,"conversation",conversation.getId());
                var future=new UiInvalidation(source.eventId(),schema,schema==1?"future.event":"inbox.invalidated",
                        source.occurredAt(),source.organizationId(),source.workspaceId(),source.resource(),audience,
                        source.recipientEmployeeIds(),source.origin(),source.actorEmployeeId(),source.correlationId());
                var output=routing.forRecipient(subject(owner),future).orElseThrow();
                assertEquals(audience==UiInvalidation.Audience.FORMER_SCOPE?"access.invalidated":"stream.resync-required",output.type());
                assertEquals(1,output.schemaVersion()); assertNull(output.resource());
                assertNull(output.workspaceId()); assertNull(output.correlationId());
                assertTrue(routing.forRecipient(subject(outsider),future).isEmpty());
                assertTrue(routing.forRecipient(AccessSubject.session(owner.getId(),Instant.now().minusSeconds(1)),future).isEmpty());
                if (audience==UiInvalidation.Audience.CONVERSATION || audience==UiInvalidation.Audience.PERSONAL
                        || audience==UiInvalidation.Audience.FORMER_SCOPE)
                    assertTrue(routing.forRecipient(subject(other),future).isEmpty());
            }
        }
    }

    @Test void installationRequiresProofVersionAndOwnerExceptExplicitRebind() throws Exception {
        UUID id=UUID.randomUUID(); String secret="a".repeat(43);
        byte[] proof=java.security.MessageDigest.getInstance("SHA-256").digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                insert into dbo.pushinstallation (id,organizationid,recipientemployeeid,platform,provider,appid,environment,
                encryptedendpoint,endpointfingerprint,activeendpointfingerprint,ownershipsecrethash,bindingversion,endpointversion,
                enabled,reportedpermission,lastseenat,activeuntil) values (?,?,?,'IOS','APNS','test','SANDBOX',?, ?, ?, ?,1,1,true,'GRANTED',clock_timestamp(),clock_timestamp()+interval '1 day')
                """,id,org.getId(),owner.getId(),new byte[]{1},"a".repeat(64),"a".repeat(64),proof);
        var creationConflict=assertThrows(InstallationAccessPolicy.VersionConflict.class,
                ()->installations.requireMutation(subject(owner),id,secret,0,InstallationAccessPolicy.Mutation.CREATE));
        assertEquals(1,creationConflict.currentVersion()); assertEquals("\"1\"",creationConflict.etag());
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(owner),id,"b".repeat(43),0,InstallationAccessPolicy.Mutation.CREATE));
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(owner),id,secret,0,InstallationAccessPolicy.Mutation.UPDATE));
        assertEquals(1,installations.requireMutation(subject(owner),id,secret,1,InstallationAccessPolicy.Mutation.UPDATE).registrationVersion());
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(other),id,secret,1,InstallationAccessPolicy.Mutation.DELETE));
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(owner),id,"b".repeat(43),1,InstallationAccessPolicy.Mutation.UPDATE));
        assertNotNull(installations.requireMutation(subject(other),id,secret,1,InstallationAccessPolicy.Mutation.REBIND));
        jdbc.update("update dbo.pushinstallation set recipientemployeeid=?,bindingversion=2,registrationversion=2 where id=?",other.getId(),id);
        var staleConflict=assertThrows(InstallationAccessPolicy.VersionConflict.class,()->installations.requireMutation(subject(owner),id,secret,1,InstallationAccessPolicy.Mutation.REBIND));
        assertEquals(2,staleConflict.currentVersion());
        var lostCreateResponse=assertThrows(InstallationAccessPolicy.VersionConflict.class,()->installations.requireMutation(subject(owner),id,secret,0,InstallationAccessPolicy.Mutation.CREATE));
        assertEquals("\"2\"",lostCreateResponse.etag());
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(owner),id,"b".repeat(43),0,InstallationAccessPolicy.Mutation.CREATE));
        assertEquals(other.getId(),jdbc.queryForObject("select recipientemployeeid from dbo.pushinstallation where id=?",UUID.class,id));
        assertThrows(AccessDeniedException.class,()->installations.requireMutation(subject(owner),id,secret,2,InstallationAccessPolicy.Mutation.DELETE));
        assertFalse(policy.allows(subject(admin),INSTALLATION,id));
    }
}
