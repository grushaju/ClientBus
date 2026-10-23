package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.notification.NotificationTargetKind;
import kit.penny.clientbus.common.notification.NotificationType;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.NotificationIdentity;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class NotificationCenterService {
    private static final String SUBJECT_ACTIVE = "u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')";
    private static final String ACCESSIBLE = " from dbo.notification n join dbo.employee e on e.employeeid=:recipient "
            + "join dbo.users u on u.userid=e.userid where " + SUBJECT_ACTIVE + " and " + AccessPolicy.NOTIFICATION_PREDICATE;
    private static final String ALERT_ACTIVE = """
            n.readat is null and n.alertinvalidatedat is null and n.alertexpiresat>clock_timestamp() and (
                (n.type='ASSIGNMENT' and exists (select 1 from dbo.conversation ca
                    where ca.conversationid=n.targetid and ca.assignedemployeeid=n.recipientemployeeid
                      and ca.assignmentoccurrenceid::text=n.templateargs->>'assignmentOccurrenceId'))
                or (n.type='INCOMING_MESSAGE' and exists (select 1 from dbo.conversation ci
                    where ci.conversationid=n.targetid and ci.assignedemployeeid=n.recipientemployeeid))
                or (n.type='OUTBOUND_FAILED' and exists (select 1 from dbo.message mf
                    where mf.messageid::text=n.templateargs->>'messageId' and mf.conversationid=n.targetid
                      and mf.sendattemptid::text=n.templateargs->>'sendAttemptId'
                      and (mf.processingstatus='FAILED' or mf.deliverystatus='FAILED')))
                or (n.type='CHANNEL_ACTION_REQUIRED' and exists (select 1 from dbo.channelaccount ac
                    join dbo.channel ch on ch.channelid=ac.channelid where ac.channelaccountid=n.targetid
                      and ch.notificationincidentid::text=n.templateargs->>'incidentId'
                      and (ch.status in ('ERROR','DISCONNECTED')
                        or (ch.status='CONNECTING' and ch.notificationincidentreason='REAUTH_REQUIRED'))
                      and not coalesce(ch.notificationexpecteddisconnect,false)))
            )
            """;
    private static final String SELECT = "select n.*, (" + ALERT_ACTIVE + ") as alert_active";
    private static final String READ_ALL_HASH = NotificationIdentity.digest("READ_ALL/v1", "ALL_ACCESSIBLE");
    public record PageCursor(int version, UUID recipient, UUID organization, boolean unreadOnly, Instant createdAt, UUID id) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final NotificationProperties config;
    private final PersonalNotificationEvents events;
    private final MeterRegistry meters;

    public NotificationCenterService(NamedParameterJdbcTemplate jdbc, JsonMapper mapper, NotificationProperties config,
            PersonalNotificationEvents events, MeterRegistry meters) {
        this.jdbc = jdbc; this.mapper = mapper; this.config = config; this.events = events; this.meters = meters;
    }

    @Transactional(readOnly = true)
    public NotificationPageDto list(AccessSubject subject, boolean unreadOnly, int limit, String cursor) {
        UUID organization = organization(subject);
        if (limit < 1 || limit > 100) throw new NotificationApiException(400, "INVALID_REQUEST");
        var parameters = parameters(subject.employeeId()).addValue("limit", limit + 1);
        String boundary = "";
        if (cursor != null) {
            PageCursor decoded = decode(cursor, subject.employeeId(), organization, unreadOnly);
            parameters.addValue("created", Timestamp.from(decoded.createdAt())).addValue("id", decoded.id());
            boundary = " and (n.createdat,n.id)<(:created,:id)";
        }
        var rows = jdbc.query(SELECT + ACCESSIBLE + (unreadOnly ? " and n.readat is null" : "") + boundary
                        + " order by n.createdat desc,n.id desc limit :limit", parameters, this::map);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (more) {
            var last = items.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                    new PageCursor(1, subject.employeeId(), organization, unreadOnly, last.createdAt(), last.id())));
        }
        return new NotificationPageDto(items, next);
    }

    @Transactional(readOnly = true)
    public NotificationSummaryDto summary(AccessSubject subject) {
        organization(subject);
        return new NotificationSummaryDto(jdbc.queryForObject("select count(*)" + ACCESSIBLE + " and n.readat is null",
                parameters(subject.employeeId()), Long.class));
    }

    @Transactional(readOnly = true)
    public NotificationDto get(AccessSubject subject, UUID id) {
        organization(subject);
        return findForRecipient(id, subject.employeeId()).orElseThrow(() -> new NotificationApiException(404, "NOT_FOUND"));
    }

    /** Explicit background identity; a worker cannot reuse a cached HTTP principal or bypass current target ACL. */
    @Transactional(readOnly = true)
    public Optional<NotificationDto> findForRecipient(UUID notificationId, UUID recipientId) {
        return jdbc.query(SELECT + ACCESSIBLE + " and n.id=:id", parameters(recipientId).addValue("id", notificationId), this::map)
                .stream().findFirst();
    }

    @Transactional
    public NotificationDto read(AccessSubject subject, UUID id) {
        UUID organization = organization(subject);
        int changed = jdbc.update("""
                update dbo.notification n set readat=clock_timestamp()
                from dbo.employee e join dbo.users u on u.userid=e.userid
                where e.employeeid=:recipient and n.id=:id and n.readat is null and
                """ + SUBJECT_ACTIVE + " and " + AccessPolicy.NOTIFICATION_PREDICATE,
                parameters(subject.employeeId()).addValue("id", id));
        var result = get(subject, id);
        if (changed > 0) {
            events.publish(organization, subject.employeeId(), id, false);
            recordRead("single");
        }
        return result;
    }

    @Transactional
    public NotificationReadAllResultDto readAll(AccessSubject subject, String idempotencyKey) {
        UUID organization = organization(subject);
        String key;
        try {
            if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException();
            key = UUID.fromString(idempotencyKey).toString();
        } catch (IllegalArgumentException error) {
            throw new NotificationApiException(400, "INVALID_REQUEST");
        }
        var parameters = parameters(subject.employeeId()).addValue("organization", organization).addValue("key", key)
                .addValue("id", UUID.randomUUID()).addValue("hash", READ_ALL_HASH)
                .addValue("retention", config.commandIdempotencyWindow().toMillis());
        boolean claimed = jdbc.update("""
                insert into dbo.notificationcommand (id,organizationid,recipientemployeeid,idempotencykey,requesthash,scope,readat,updatedcount,retainuntil)
                values (:id,:organization,:recipient,:key,:hash,'ALL_ACCESSIBLE',clock_timestamp(),0,
                    clock_timestamp()+(:retention*interval '1 millisecond'))
                on conflict (organizationid,recipientemployeeid,idempotencykey) do nothing
                """, parameters) == 1;
        if (!claimed) {
            return jdbc.queryForObject("""
                    select requesthash,scope,readat,updatedcount from dbo.notificationcommand
                    where organizationid=:organization and recipientemployeeid=:recipient and idempotencykey=:key
                    """, parameters, (rs, row) -> {
                if (!READ_ALL_HASH.equals(rs.getString("requesthash")) || !"ALL_ACCESSIBLE".equals(rs.getString("scope")))
                    throw new NotificationApiException(409, "IDEMPOTENCY_CONFLICT");
                return new NotificationReadAllResultDto(rs.getTimestamp("readat").toInstant(), rs.getLong("updatedcount"));
            });
        }
        // The UPDATE has its own READ COMMITTED snapshot, including rows committed while the command claim waited.
        // A materialized timestamp supplies one readAt for every marked row and for the persisted retry result.
        var result = jdbc.queryForObject("""
                with moment as materialized (select clock_timestamp() as at), marked as (
                    update dbo.notification n set readat=moment.at
                    from dbo.employee e join dbo.users u on u.userid=e.userid cross join moment
                    where e.employeeid=:recipient and n.readat is null and
                """ + SUBJECT_ACTIVE + " and " + AccessPolicy.NOTIFICATION_PREDICATE + """
                    returning n.id
                ) select moment.at as readat,(select count(*) from marked) as updatedcount from moment
                """, parameters, (rs, row) -> new NotificationReadAllResultDto(rs.getTimestamp("readat").toInstant(), rs.getLong("updatedcount")));
        parameters.addValue("readAt", Timestamp.from(result.readAt())).addValue("count", result.updatedCount());
        jdbc.update("update dbo.notificationcommand set readat=:readAt,updatedcount=:count where id=:id", parameters);
        if (result.updatedCount() > 0) events.publish(organization, subject.employeeId(), null, false);
        recordRead("all");
        return result;
    }

    private void recordRead(String scope) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { meters.counter("clientbus.notification.read", "scope", scope).increment(); }
        });
    }

    private UUID organization(AccessSubject subject) {
        if (subject.expired(Instant.now())) throw new NotificationApiException(401, "UNAUTHENTICATED");
        return jdbc.query("select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid "
                        + "where e.employeeid=:recipient and " + SUBJECT_ACTIVE,
                parameters(subject.employeeId()), (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst()
                .orElseThrow(() -> new NotificationApiException(403, "FORBIDDEN"));
    }

    private MapSqlParameterSource parameters(UUID recipient) {
        return new MapSqlParameterSource("recipient", recipient);
    }

    private PageCursor decode(String value, UUID recipient, UUID organization, boolean unreadOnly) {
        try {
            if (value.isEmpty() || value.length() > 1024 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            var cursor = mapper.readValue(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8), PageCursor.class);
            if (cursor.version() != 1 || !recipient.equals(cursor.recipient()) || !organization.equals(cursor.organization())
                    || unreadOnly != cursor.unreadOnly() || cursor.createdAt() == null || cursor.id() == null)
                throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException error) { throw new NotificationApiException(400, "INVALID_CURSOR"); }
    }

    private NotificationDto map(ResultSet rs, int row) throws SQLException {
        Instant readAt = rs.getTimestamp("readat") == null ? null : rs.getTimestamp("readat").toInstant();
        Map<String, String> args = mapper.readValue(rs.getString("templateargs"), new TypeReference<>() {});
        return new NotificationDto(rs.getObject("id", UUID.class), NotificationType.valueOf(rs.getString("type")),
                rs.getObject("workspaceid", UUID.class), new NotificationTargetDto(NotificationTargetKind.valueOf(rs.getString("targetkind")),
                rs.getObject("targetid", UUID.class)), args, rs.getTimestamp("createdat").toInstant(), readAt,
                rs.getTimestamp("alertexpiresat").toInstant(), rs.getBoolean("alert_active"));
    }
}
