package kit.penny.clientbus.server.security.policy;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Authoritative scalar SQL: no principal role cache, JPA identity map or Kafka cache. */
@Service
public class AccessPolicy {
    public enum Resource {
        ORGANIZATION, WORKSPACE, WORKSPACE_MANAGEMENT, WORKSPACE_ADMIN, CONVERSATION,
        ASSIGNED_CONVERSATION, CONVERSATION_SUPERVISORS,
        CHANNEL, CHANNEL_MANAGEMENT, CHANNEL_ACCOUNT, CHANNEL_ACCOUNT_MANAGEMENT,
        PERSONAL_GROUP, SAVED_FILTER, EMPLOYEE, NOTIFICATION, HISTORY_OPERATION, INSTALLATION
    }

    private final NamedParameterJdbcTemplate jdbc;
    public AccessPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SUBJECT = " from dbo.employee e join dbo.users u on u.userid=e.userid ";
    private static final String ACTIVE = "u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')";
    private static final String MEMBER = "exists (select 1 from dbo.employeeworkspace m where m.employeeid=e.employeeid and m.workspaceid=w.workspaceid)";
    private static final String SUPERVISOR = "exists (select 1 from dbo.employeeworkspace m where m.employeeid=e.employeeid and m.workspaceid=w.workspaceid and m.issupervisor)";
    private static final String WORKSPACE = "w.organizationid=e.organizationid and (u.role='SUPER_ADMIN' or " + MEMBER + ")";
    private static final String CONVERSATION = WORKSPACE + " and (u.role='SUPER_ADMIN' or " + SUPERVISOR
            + " or c.assignedemployeeid is null or c.assignedemployeeid=e.employeeid)";

    /** Same predicate is intended for list/count/read/read-all; outer aliases n/e/u are fixed. */
    public static final String NOTIFICATION_PREDICATE = "n.organizationid=e.organizationid and n.recipientemployeeid=e.employeeid and ("
            + "(n.targetkind='CONVERSATION' and exists (select 1 from dbo.conversation c join dbo.workspace w on w.workspaceid=c.workspaceid where c.conversationid=n.targetid and n.workspaceid=w.workspaceid and " + CONVERSATION + ")) or "
            + "(n.targetkind='CHANNEL_ACCOUNT' and exists (select 1 from dbo.channelaccount a join dbo.channel ch on ch.channelid=a.channelid join dbo.workspace w on w.workspaceid=ch.workspaceid where a.channelaccountid=n.targetid and n.workspaceid=w.workspaceid and " + WORKSPACE + " and u.role='SUPER_ADMIN')) or "
            + "(n.targetkind='HISTORY_OPERATION' and exists (select 1 from dbo.historysyncoperation h join dbo.conversation c on c.conversationid=h.conversationid join dbo.workspace w on w.workspaceid=c.workspaceid where h.id=n.targetid and h.initiatoremployeeid=e.employeeid and h.organizationid=e.organizationid and h.workspaceid=w.workspaceid and n.workspaceid=w.workspaceid and " + CONVERSATION + ")))";

    private static String predicate(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> "e.organizationid=:id";
            case WORKSPACE, WORKSPACE_MANAGEMENT, WORKSPACE_ADMIN -> "exists (select 1 from dbo.workspace w where w.workspaceid=:id and " + WORKSPACE
                    + (resource == Resource.WORKSPACE_MANAGEMENT ? " and (u.role='SUPER_ADMIN' or " + SUPERVISOR + ")" : "")
                    + (resource == Resource.WORKSPACE_ADMIN ? " and u.role='SUPER_ADMIN'" : "") + ")";
            case CONVERSATION -> "exists (select 1 from dbo.conversation c join dbo.workspace w on w.workspaceid=c.workspaceid where c.conversationid=:id and " + CONVERSATION + ")";
            case ASSIGNED_CONVERSATION -> "exists (select 1 from dbo.conversation c join dbo.workspace w on w.workspaceid=c.workspaceid where c.conversationid=:id and " + WORKSPACE + " and c.assignedemployeeid=e.employeeid and u.role='EMPLOYEE')";
            case CONVERSATION_SUPERVISORS -> "exists (select 1 from dbo.conversation c join dbo.workspace w on w.workspaceid=c.workspaceid where c.conversationid=:id and " + WORKSPACE + " and (u.role='SUPER_ADMIN' or " + SUPERVISOR + "))";
            case CHANNEL, CHANNEL_MANAGEMENT, CHANNEL_ACCOUNT, CHANNEL_ACCOUNT_MANAGEMENT -> {
                boolean account = resource == Resource.CHANNEL_ACCOUNT || resource == Resource.CHANNEL_ACCOUNT_MANAGEMENT;
                boolean manage = resource == Resource.CHANNEL_MANAGEMENT || resource == Resource.CHANNEL_ACCOUNT_MANAGEMENT;
                yield "exists (select 1 from dbo.channel ch join dbo.workspace w on w.workspaceid=ch.workspaceid "
                        + (account ? "join dbo.channelaccount a on a.channelid=ch.channelid where a.channelaccountid=:id" : "where ch.channelid=:id")
                        + " and " + WORKSPACE + (manage ? " and u.role='SUPER_ADMIN'" : "") + ")";
            }
            case PERSONAL_GROUP -> "exists (select 1 from dbo.conversationgroup g join dbo.workspace w on w.workspaceid=g.workspaceid where g.conversationgroupid=:id and g.owneremployeeid=e.employeeid and u.role='EMPLOYEE' and " + WORKSPACE + ")";
            case SAVED_FILTER -> "exists (select 1 from dbo.savedconversationfilter f join dbo.workspace w on w.workspaceid=f.workspaceid where f.savedconversationfilterid=:id and (f.filtertype='SYSTEM' or (f.filtertype='PRIVATE' and f.owneremployeeid=e.employeeid)) and " + WORKSPACE + ")";
            case EMPLOYEE -> "exists (select 1 from dbo.employee target where target.employeeid=:id and target.organizationid=e.organizationid and (target.employeeid=e.employeeid or u.role='SUPER_ADMIN' or exists (select 1 from dbo.employeeworkspace tm join dbo.workspace w on w.workspaceid=tm.workspaceid where tm.employeeid=target.employeeid and w.organizationid=e.organizationid and " + SUPERVISOR + ")))";
            case NOTIFICATION -> "exists (select 1 from dbo.notification n where n.id=:id and " + NOTIFICATION_PREDICATE + ")";
            case HISTORY_OPERATION -> "exists (select 1 from dbo.historysyncoperation h join dbo.conversation c on c.conversationid=h.conversationid join dbo.workspace w on w.workspaceid=c.workspaceid where h.id=:id and h.initiatoremployeeid=e.employeeid and h.organizationid=e.organizationid and h.workspaceid=w.workspaceid and " + CONVERSATION + ")";
            case INSTALLATION -> "exists (select 1 from dbo.pushinstallation i where i.id=:id and i.recipientemployeeid=e.employeeid and i.organizationid=e.organizationid)";
        };
    }

    public boolean allows(AccessSubject subject, Resource resource, UUID id) {
        Objects.requireNonNull(subject); Objects.requireNonNull(resource); Objects.requireNonNull(id);
        if (subject.expired(Instant.now())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1" + SUBJECT + "where " + ACTIVE
                + " and e.employeeid=:employee and " + predicate(resource) + ")",
                Map.of("employee", subject.employeeId(), "id", id), Boolean.class));
    }

    public void require(AccessSubject subject, Resource resource, UUID id) {
        if (!allows(subject, resource, id)) throw new AccessDeniedException("Access denied");
    }

    /** Current DB authorization for a claimed delivery, independent of HTTP authentication. */
    public boolean allowsPush(UUID deliveryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1" + SUBJECT + """
                join dbo.notification n on n.recipientemployeeid=e.employeeid
                join dbo.notificationdelivery d on d.notificationid=n.id
                join dbo.pushinstallation i on i.id=d.registrationid
                where d.id=:id and
                """ + ACTIVE + " and " + NOTIFICATION_PREDICATE + """
                  and i.recipientemployeeid=e.employeeid and i.organizationid=e.organizationid
                  and i.bindingversion=d.bindingversion and i.enabled
                  and i.activeuntil>clock_timestamp() and i.reportedpermission in ('GRANTED','PROVISIONAL')
                  and n.readat is null and n.alertinvalidatedat is null
                  and n.alertexpiresat>clock_timestamp() and d.expiresat>clock_timestamp()
                  and d.status='IN_FLIGHT' and d.attemptendpointversion=i.endpointversion
                )
                """, Map.of("id", deliveryId), Boolean.class));
    }

    /** One DB statement for at most 500 candidates; callers chunk gateway recipients. */
    public Set<UUID> allowedRecipients(Collection<UUID> candidates, Resource resource, UUID id) {
        Objects.requireNonNull(resource); Objects.requireNonNull(id);
        if (candidates.size() > 500) throw new IllegalArgumentException("At most 500 candidates");
        if (candidates.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.queryForList("select e.employeeid" + SUBJECT + "where " + ACTIVE
                + " and e.employeeid in (:candidates) and " + predicate(resource),
                Map.of("candidates", candidates, "id", id), UUID.class));
    }

    /** For trusted source writers resolving the initial audience, never a cached delivery permit. */
    public Set<UUID> audience(Resource resource, UUID id) {
        return Set.copyOf(jdbc.queryForList("select e.employeeid" + SUBJECT + "where " + ACTIVE
                + " and " + predicate(resource), Map.of("id", id), UUID.class));
    }
}
