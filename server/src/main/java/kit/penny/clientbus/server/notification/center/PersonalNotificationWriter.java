package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.common.notification.NotificationTargetKind;
import kit.penny.clientbus.common.notification.NotificationType;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.NotificationAudienceResolver;
import kit.penny.clientbus.server.notification.contract.NotificationIdentity;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.notification.contract.OutboundAttemptContext;
import kit.penny.clientbus.server.notification.push.PushIntentWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PersonalNotificationWriter {
    private record Scope(UUID organization, UUID workspace, UUID target) {}
    private record Attempt(UUID conversationId, Instant startedAt) {}
    private record Incident(Scope scope, Instant startedAt) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties config;
    private final NotificationAudienceResolver audiences;
    private final PushIntentWriter push;
    private final PersonalNotificationEvents events;
    private final JsonMapper mapper;
    private final MeterRegistry meters;

    public PersonalNotificationWriter(NamedParameterJdbcTemplate jdbc, NotificationProperties config,
            NotificationAudienceResolver audiences, PushIntentWriter push, PersonalNotificationEvents events,
            JsonMapper mapper, MeterRegistry meters) {
        this.jdbc = jdbc; this.config = config; this.audiences = audiences; this.push = push;
        this.events = events; this.mapper = mapper; this.meters = meters;
    }

    public void assignmentChanged(UUID conversationId, UUID occurrenceId, UUID actorEmployeeId) {
        var scope = conversation(conversationId);
        if (scope == null) return;
        String key = NotificationIdentity.digest("N1/v1", conversationId.toString(), occurrenceId.toString());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select assignmentoccurrenceid=:occurrence from dbo.conversation where conversationid=:id",
                Map.of("occurrence", occurrenceId, "id", conversationId), Boolean.class))) return;
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from dbo.notificationoccurrence where organizationid=:org and occurrencekey=:key)",
                Map.of("org", scope.organization(), "key", key), Boolean.class))) return;
        close("targetkind='CONVERSATION' and targetid=:target and type in ('ASSIGNMENT','INCOMING_MESSAGE','OUTBOUND_FAILED')",
                Map.of("target", conversationId));
        Timestamp observedAt = jdbc.queryForObject("select assignmentobservedat from dbo.conversation where conversationid=:id",
                Map.of("id", conversationId), Timestamp.class);
        if (observedAt == null) return;
        create(scope, key,
                NotificationType.ASSIGNMENT, NotificationTargetKind.CONVERSATION,
                audiences.assignment(conversationId, actorEmployeeId), Map.of("assignmentOccurrenceId", occurrenceId.toString()),
                config.assignmentAlertTtl(), live(observedAt.toInstant(), occurrenceId, actorEmployeeId));
    }

    public void incomingMessage(UUID conversationId, UUID channelAccountId, String externalId, NotificationSourceContext source) {
        if (source.origin() != EventOrigin.LIVE) return;
        var scope = conversation(conversationId);
        if (scope == null || externalId == null || externalId.isBlank()) return;
        Boolean matches = jdbc.queryForObject("select channelaccountid=:account from dbo.conversation where conversationid=:id",
                Map.of("account", channelAccountId, "id", conversationId), Boolean.class);
        if (!Boolean.TRUE.equals(matches)) throw new IllegalArgumentException("Conversation channel mismatch");
        create(scope, NotificationIdentity.incoming(channelAccountId, conversationId, externalId), NotificationType.INCOMING_MESSAGE,
                NotificationTargetKind.CONVERSATION, audiences.incoming(conversationId), Map.of(),
                config.incomingAlertTtl(), source);
    }

    public void outboundFailed(UUID messageId, UUID sendAttemptId) {
        var messages = jdbc.query("""
                select conversationid,sendattemptstartedat from dbo.message where messageid=:message and sendattemptid=:attempt
                  and (processingstatus='FAILED' or deliverystatus='FAILED') for update
                """, Map.of("message", messageId, "attempt", sendAttemptId),
                (rs, row) -> new Attempt(rs.getObject(1, UUID.class), instant(rs, "sendattemptstartedat")));
        if (messages.isEmpty() || messages.getFirst().startedAt() == null) return;
        var scope = conversation(messages.getFirst().conversationId());
        if (scope == null) return;
        create(scope, NotificationIdentity.outboundFailure(new OutboundAttemptContext(messageId, sendAttemptId)),
                NotificationType.OUTBOUND_FAILED, NotificationTargetKind.CONVERSATION,
                audiences.outboundFailure(scope.target()), Map.of("messageId", messageId.toString(), "sendAttemptId", sendAttemptId.toString()),
                config.failureAlertTtl(), live(messages.getFirst().startedAt(), sendAttemptId, null));
    }

    public void closeOutboundAttempt(UUID messageId, UUID sendAttemptId) {
        close("type='OUTBOUND_FAILED' and templateargs->>'messageId'=:message and templateargs->>'sendAttemptId'=:attempt",
                Map.of("message", messageId.toString(), "attempt", sendAttemptId.toString()));
    }

    public void channelIncident(UUID channelAccountId, UUID incidentId, NotificationSourceContext source) {
        if (source.origin() != EventOrigin.LIVE) return;
        var rows = jdbc.query("""
                select w.organizationid,w.workspaceid,ch.notificationincidentstartedat from dbo.channelaccount a join dbo.channel ch on ch.channelid=a.channelid
                join dbo.workspace w on w.workspaceid=ch.workspaceid where a.channelaccountid=:id
                  and ch.notificationincidentid=:incident and (ch.status in ('ERROR','DISCONNECTED')
                    or (ch.status='CONNECTING' and ch.notificationincidentreason='REAUTH_REQUIRED'))
                  and not coalesce(ch.notificationexpecteddisconnect,false) for update of ch
                """, Map.of("id", channelAccountId, "incident", incidentId),
                (rs, row) -> new Incident(new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), channelAccountId),
                        instant(rs, "notificationincidentstartedat")));
        if (rows.isEmpty() || rows.getFirst().startedAt() == null) return;
        create(rows.getFirst().scope(), NotificationIdentity.digest("N4/v1", channelAccountId.toString(), incidentId.toString()),
                NotificationType.CHANNEL_ACTION_REQUIRED, NotificationTargetKind.CHANNEL_ACCOUNT,
                audiences.channelIncident(channelAccountId), Map.of("incidentId", incidentId.toString()), config.channelAlertTtl(),
                live(rows.getFirst().startedAt(), source.sourceEventId(), source.actorEmployeeId()));
    }

    public void closeChannelIncident(UUID channelAccountId, UUID incidentId) {
        close("type='CHANNEL_ACTION_REQUIRED' and targetid=:target and templateargs->>'incidentId'=:incident",
                Map.of("target", channelAccountId, "incident", incidentId.toString()));
    }

    private Scope conversation(UUID id) {
        return jdbc.query("""
                select w.organizationid,w.workspaceid,c.conversationid from dbo.conversation c
                join dbo.workspace w on w.workspaceid=c.workspaceid where c.conversationid=:id for update of c
                """, Map.of("id", id), (rs, row) -> new Scope(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class))).stream().findFirst().orElse(null);
    }

    private NotificationSourceContext live(Instant observedAt, UUID event, UUID actor) {
        return new NotificationSourceContext(EventOrigin.LIVE, observedAt, event, actor);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Instant now() {
        return jdbc.queryForObject("select clock_timestamp()", Map.of(), Timestamp.class).toInstant();
    }

    private void create(Scope scope, String key, NotificationType type, NotificationTargetKind targetKind,
            Set<UUID> audience, Map<String, String> args, Duration ttl, NotificationSourceContext source) {
        Instant at = now();
        if (source.origin() != EventOrigin.LIVE || source.firstObservedAt().isBefore(at.minus(config.sourceReplayWindow()))) return;
        Set<UUID> recipients = config.creationEnabled() ? audience : Set.of();
        UUID occurrence = UUID.randomUUID();
        String recipientArray = "{" + String.join(",", recipients.stream().map(UUID::toString).sorted().toList()) + "}";
        var parameters = new MapSqlParameterSource().addValue("id", occurrence).addValue("organization", scope.organization())
                .addValue("workspace", scope.workspace()).addValue("key", key).addValue("recipients", recipientArray)
                .addValue("dedupUntil", Timestamp.from(at.plus(config.dedupRetention())));
        if (jdbc.update("""
                insert into dbo.notificationoccurrence (id,organizationid,workspaceid,occurrencekey,origin,recipientemployeeids,dedupuntil)
                values (:id,:organization,:workspace,:key,'LIVE',cast(:recipients as uuid[]),:dedupUntil)
                on conflict (organizationid,occurrencekey) do nothing
                """, parameters) == 0) return;
        for (UUID recipient : recipients) {
            UUID dedup = UUID.randomUUID();
            parameters.addValue("dedup", dedup).addValue("recipient", recipient);
            if (jdbc.update("""
                    insert into dbo.notificationdedup (id,occurrenceid,organizationid,recipientemployeeid,deduplicationkey,dedupuntil)
                    values (:dedup,:id,:organization,:recipient,:key,:dedupUntil)
                    on conflict (organizationid,recipientemployeeid,deduplicationkey) do nothing
                    """, parameters) == 0) continue;
            UUID notification = UUID.randomUUID();
            parameters.addValue("notification", notification).addValue("type", type.name()).addValue("targetKind", targetKind.name())
                    .addValue("target", scope.target()).addValue("args", mapper.writeValueAsString(args))
                    .addValue("expires", Timestamp.from(at.plus(ttl))).addValue("retain", Timestamp.from(at.plus(config.centerRetention())));
            jdbc.update("""
                    insert into dbo.notification (id,dedupid,organizationid,recipientemployeeid,workspaceid,type,targetkind,targetid,
                        templateargs,alertexpiresat,retainuntil)
                    values (:notification,:dedup,:organization,:recipient,:workspace,:type,:targetKind,:target,cast(:args as jsonb),:expires,:retain)
                    """, parameters);
            push.createIntents(notification);
            events.publish(scope.organization(), recipient, notification, true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { meters.counter("clientbus.notification.created", "type", type.name()).increment(); }
            });
        }
    }

    private void close(String predicate, Map<String, ?> parameters) {
        var rows = jdbc.query("update dbo.notification set alertinvalidatedat=clock_timestamp() "
                        + "where alertinvalidatedat is null and " + predicate + " returning organizationid,recipientemployeeid",
                parameters, (rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        rows.stream().distinct().forEach(row -> events.publish(row.getKey(), row.getValue(), null, false));
    }
}
