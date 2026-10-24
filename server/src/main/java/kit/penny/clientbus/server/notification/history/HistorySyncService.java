package kit.penny.clientbus.server.notification.history;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import kit.penny.clientbus.server.notification.contract.NotificationIdentity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.security.policy.*;
import kit.penny.clientbus.server.service.ChannelAvailabilityService;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable operation state. Network calls never run in these transactions. */
@Service
public class HistorySyncService {
    public record Operation(UUID id, UUID organization, UUID workspace, UUID conversation, UUID account, UUID initiator,
            String key, String hash, String before, int limit, HistoryOperationStatus status, int attempts, long version,
            Instant lease, Instant deadline, Instant created, Instant updated, HistorySyncResultDto result, String error) {
        public HistorySyncOperationDto dto() {
            return new HistorySyncOperationDto(id, conversation, status, created, updated, deadline, result, error);
        }
        public HistorySyncCommand command() {
            return new HistorySyncCommand(id, initiator, organization, workspace, conversation, account,
                    new HistorySyncRequest(before, limit), EventOrigin.HISTORY_IMPORT, deadline);
        }
    }
    public record Execution(ChannelType type, String externalId) {}
    public record Cursor(int version, UUID organization, UUID initiator, UUID conversation, boolean activeOnly,
            Instant createdAt, UUID id) {}
    public static class StaleClaim extends RuntimeException {}

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties config;
    private final HistorySyncSettings limits;
    private final JsonMapper mapper;
    private final AccessPolicy policy;
    private final ConversationRepository conversations;
    private final ChannelAvailabilityService availability;
    private final IMessageProcessingService messages;
    private final HistorySyncEvents events;

    public HistorySyncService(NamedParameterJdbcTemplate jdbc, NotificationProperties config, HistorySyncSettings limits,
            JsonMapper mapper, AccessPolicy policy, ConversationRepository conversations,
            ChannelAvailabilityService availability, IMessageProcessingService messages, HistorySyncEvents events) {
        this.jdbc = jdbc; this.config = config; this.limits = limits; this.mapper = mapper; this.policy = policy;
        this.conversations = conversations; this.availability = availability; this.messages = messages; this.events = events;
    }

    @Transactional
    public HistorySyncOperationDto submit(AccessSubject subject, UUID conversation, String key, HistorySyncRequest input) {
        UUID organization = organization(subject);
        requireConversation(subject, conversation);
        var request = normalize(input);
        String normalizedKey = key(key);
        String hash = hash(conversation, request);
        // Serializes admission for one actor, including requests for different conversations.
        jdbc.queryForList("select employeeid from dbo.employee where employeeid=:actor for update", Map.of("actor", subject.employeeId()));
        organization = organization(subject);
        requireConversation(subject, conversation);
        var parameters = new MapSqlParameterSource().addValue("org", organization).addValue("actor", subject.employeeId())
                .addValue("key", normalizedKey).addValue("conversation", conversation);
        var existing = jdbc.query("select * from dbo.historysyncoperation where organizationid=:org and initiatoremployeeid=:actor and idempotencykey=:key",
                parameters, this::map);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().hash().equals(hash)) throw new NotificationApiException(409, "IDEMPOTENCY_CONFLICT");
            return existing.getFirst().dto();
        }
        if (!config.historySubmissionEnabled()) throw new NotificationApiException(503, "UNAVAILABLE");
        var target = conversations.findByIdForUpdate(conversation).orElseThrow(() -> new NotificationApiException(404, "NOT_FOUND"));
        requireConversation(subject, conversation);
        if (jdbc.queryForObject("select count(*) from dbo.historysyncoperation where status in ('PENDING','RUNNING') and deadlineat>clock_timestamp() and initiatoremployeeid=:actor",
                    parameters, Long.class) >= limits.maxActivePerEmployee()
                || jdbc.queryForObject("select count(*) from dbo.historysyncoperation where status in ('PENDING','RUNNING') and deadlineat>clock_timestamp() and conversationid=:conversation",
                    parameters, Long.class) >= limits.maxActivePerConversation())
            throw new NotificationApiException(429, "RATE_LIMITED");
        if (!target.isHistoryStartReached()) availability.requireConnected(target.getChannelAccount().getId());
        UUID id = UUID.randomUUID();
        parameters.addValue("id", id).addValue("workspace", target.getWorkspace().getId())
                .addValue("account", target.getChannelAccount().getId()).addValue("hash", hash)
                .addValue("before", request.beforeExternalId()).addValue("limit", request.limit())
                .addValue("deadline", config.historyDeadline().toMillis())
                .addValue("retention", Math.max(config.historyRetention().toMillis(), config.historyIdempotencyWindow().toMillis()));
        jdbc.update("""
                insert into dbo.historysyncoperation
                  (id,organizationid,workspaceid,conversationid,channelaccountid,initiatoremployeeid,idempotencykey,requesthash,
                   beforeexternalid,requestlimit,status,attempts,claimversion,createdat,updatedat,deadlineat,retainuntil)
                select :id,:org,:workspace,:conversation,:account,:actor,:key,:hash,:before,:limit,'PENDING',0,0,
                       at,at,at+(:deadline*interval '1 millisecond'),at+((:deadline+:retention)*interval '1 millisecond')
                from (select clock_timestamp() as at) moment
                """, parameters);
        Operation operation = find(id).orElseThrow();
        if (target.isHistoryStartReached()) {
            succeed(operation, 0, true);
        } else {
            UUID eventId = UUID.randomUUID();
            String payload = mapper.writeValueAsString(new KafkaEvent<>(eventId, KafkaEventType.HISTORY_SYNC_OPERATION,
                    1, operation.created(), id, operation.command()));
            if (payload.getBytes(StandardCharsets.UTF_8).length > config.maxOutboxBytes()) throw new IllegalArgumentException("History command too large");
            jdbc.update("""
                    insert into dbo.notificationoutbox
                      (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,nextattemptat,operationid)
                    values (:id,:event,:org,:topic,'HISTORY_SYNC_OPERATION',1,cast(:payload as jsonb),'PENDING',0,0,clock_timestamp(),:operation)
                    """, Map.of("id", UUID.randomUUID(), "event", eventId, "org", organization, "topic", HistorySyncConfiguration.TOPIC,
                    "payload", payload, "operation", id));
        }
        return find(id).orElseThrow().dto();
    }

    @Transactional(readOnly = true)
    public HistorySyncOperationDto get(AccessSubject subject, UUID id) {
        organization(subject);
        if (!policy.allows(subject, AccessPolicy.Resource.HISTORY_OPERATION, id)) throw new NotificationApiException(404, "NOT_FOUND");
        return find(id).orElseThrow(() -> new NotificationApiException(404, "NOT_FOUND")).dto();
    }

    @Transactional(readOnly = true)
    public HistorySyncOperationPageDto list(AccessSubject subject, UUID conversation, boolean activeOnly, int limit, String cursor) {
        UUID organization = organization(subject);
        requireConversation(subject, conversation);
        if (limit < 1 || limit > 100) throw new NotificationApiException(400, "INVALID_REQUEST");
        var parameters = new MapSqlParameterSource().addValue("org", organization).addValue("actor", subject.employeeId())
                .addValue("conversation", conversation).addValue("limit", limit + 1);
        String boundary = "";
        if (cursor != null) {
            try {
                if (cursor.length() > 1024) throw new IllegalArgumentException();
                Cursor decoded = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
                if (decoded.version() != 1 || !organization.equals(decoded.organization()) || !subject.employeeId().equals(decoded.initiator())
                        || !conversation.equals(decoded.conversation()) || activeOnly != decoded.activeOnly()
                        || decoded.createdAt() == null || decoded.id() == null) throw new IllegalArgumentException();
                parameters.addValue("created", Timestamp.from(decoded.createdAt())).addValue("id", decoded.id());
                boundary = " and (createdat,id)<(:created,:id)";
            } catch (RuntimeException invalid) { throw new NotificationApiException(400, "INVALID_CURSOR"); }
        }
        var rows = jdbc.query("select * from dbo.historysyncoperation where organizationid=:org and initiatoremployeeid=:actor and conversationid=:conversation"
                + " and workspaceid=(select workspaceid from dbo.conversation where conversationid=:conversation)"
                + (activeOnly ? " and status in ('PENDING','RUNNING')" : "") + boundary + " order by createdat desc,id desc limit :limit", parameters, this::map);
        var items = rows.stream().limit(limit).map(Operation::dto).toList();
        String next = null;
        if (rows.size() > limit) {
            var last = items.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                    new Cursor(1, organization, subject.employeeId(), conversation, activeOnly, last.createdAt(), last.id())));
        }
        return new HistorySyncOperationPageDto(items, next);
    }

    public Optional<Operation> find(UUID id) {
        return jdbc.query("select * from dbo.historysyncoperation where id=:id", Map.of("id", id), this::map).stream().findFirst();
    }

    @Transactional
    public Optional<Operation> claim(UUID id) {
        var rows = jdbc.query("""
                select * from dbo.historysyncoperation where id=:id and deadlineat>clock_timestamp()
                  and (status='PENDING' or (status='RUNNING' and leaseuntil<=clock_timestamp()))
                for update skip locked
                """, Map.of("id", id), this::map);
        if (rows.isEmpty()) return Optional.empty();
        var operation = rows.getFirst();
        if (operation.attempts() >= limits.maxAttempts()) { failLocked(operation, "IMPORT_FAILED"); return Optional.empty(); }
        jdbc.update("""
                update dbo.historysyncoperation set status='RUNNING',claimversion=claimversion+1,attempts=attempts+1,
                  leaseuntil=least(deadlineat,clock_timestamp()+(:lease*interval '1 millisecond')),updatedat=clock_timestamp()
                where id=:id
                """, Map.of("id", id, "lease", config.lease().toMillis()));
        events.changed(operation.organization(), operation.initiator(), id);
        return find(id);
    }

    @Transactional
    public boolean renew(Operation claim) {
        return jdbc.update("""
                update dbo.historysyncoperation set leaseuntil=least(deadlineat,clock_timestamp()+(:lease*interval '1 millisecond'))
                where id=:id and status='RUNNING' and claimversion=:version and leaseuntil>clock_timestamp() and deadlineat>clock_timestamp()
                """, Map.of("id", claim.id(), "version", claim.version(), "lease", config.lease().toMillis())) == 1;
    }

    @Transactional(readOnly = true)
    public Execution execution(Operation claim) {
        if (!valid(claim)) throw new StaleClaim();
        if (!policy.allows(AccessSubject.recipient(claim.initiator()), AccessPolicy.Resource.HISTORY_OPERATION, claim.id()))
            throw new NotificationApiException(403, "ACCESS_REVOKED");
        var target = conversations.findById(claim.conversation()).orElseThrow(() -> new NotificationApiException(403, "ACCESS_REVOKED"));
        checkScope(claim, target);
        availability.requireConnected(claim.account());
        return new Execution(target.getChannelAccount().getChannel().getType(), target.getClientAccount().getExternalId());
    }

    /** Conversation -> operation is the lock order; every imported batch is fenced, not only its completion. */
    @Transactional
    public void importBatch(Operation claim, List<PlatformMessageRequest> batch, Execution execution,
            boolean last, int importedCount, boolean historyStartReached) {
        var target = conversations.findByIdForUpdate(claim.conversation()).orElseThrow(() -> new NotificationApiException(403, "ACCESS_REVOKED"));
        var locked = lock(claim.id()).orElseThrow(StaleClaim::new);
        if (locked.version() != claim.version() || !valid(claim)) throw new StaleClaim();
        if (!policy.allows(AccessSubject.recipient(claim.initiator()), AccessPolicy.Resource.HISTORY_OPERATION, claim.id()))
            throw new NotificationApiException(403, "ACCESS_REVOKED");
        checkScope(claim, target);
        if (!execution.externalId().equals(target.getClientAccount().getExternalId())
                || execution.type() != target.getChannelAccount().getChannel().getType())
            throw new NotificationApiException(403, "ACCESS_REVOKED");
        for (var message : batch) {
            if (message == null || !claim.account().equals(message.channelAccountId())
                    || !execution.externalId().equals(message.clientExternalId()) || message.externalId() == null)
                throw new IllegalArgumentException("History result scope mismatch");
            messages.processPlatformMessage(message.withOrigin(EventOrigin.HISTORY_IMPORT));
        }
        if (!valid(claim)) throw new StaleClaim(); // Rolls this batch back if its deadline elapsed during persistence.
        if (last) {
            if (historyStartReached) target.setHistoryStartReached(true);
            conversations.flush();
            succeed(locked, importedCount, historyStartReached);
        }
    }

    @Transactional
    public void fail(Operation claim, String code) {
        var operation = lock(claim.id());
        if (operation.isPresent() && operation.get().status() == HistoryOperationStatus.RUNNING
                && operation.get().version() == claim.version() && valid(claim)) failLocked(operation.get(), code);
    }

    public boolean matches(KafkaEvent<HistorySyncCommand> event) {
        if (event == null || event.eventType() != KafkaEventType.HISTORY_SYNC_OPERATION || event.schemaVersion() != 1
                || event.eventId() == null || event.payload() == null || event.payload().operationId() == null
                || !event.payload().operationId().equals(event.correlationId()) || event.occurredAt() == null) return false;
        var operation = find(event.payload().operationId());
        return operation.isPresent() && operation.get().command().equals(event.payload())
                && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from dbo.notificationoutbox where operationid=:id and eventid=:event and destination=:topic)",
                    Map.of("id", operation.get().id(), "event", event.eventId(), "topic", HistorySyncConfiguration.TOPIC), Boolean.class));
    }

    public boolean canPublish(UUID operation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from dbo.historysyncoperation where id=:id and status in ('PENDING','RUNNING') and deadlineat>clock_timestamp())",
                Map.of("id", operation), Boolean.class));
    }

    @Transactional
    public void publicationFailed(kit.penny.clientbus.server.notification.outbox.UiOutboxStore.Claim claim, String code) {
        var operation = lock(claim.operationId());
        int changed = jdbc.update("""
                update dbo.notificationoutbox set status='QUARANTINED',leaseuntil=null,errorcode=:code
                where id=:id and status='IN_FLIGHT' and claimversion=:version
                """, Map.of("id", claim.id(), "version", claim.version(), "code", code));
        if (changed == 1) operation.filter(op -> op.status() == HistoryOperationStatus.PENDING)
                .ifPresent(op -> failLocked(op, Boolean.TRUE.equals(jdbc.queryForObject(
                        "select deadlineat<=clock_timestamp() from dbo.historysyncoperation where id=:id", Map.of("id", op.id()), Boolean.class))
                        ? "DEADLINE_EXCEEDED" : "PUBLICATION_FAILED"));
    }

    @Transactional
    public void deadLetter(KafkaEvent<HistorySyncCommand> event) {
        if (matches(event)) lock(event.payload().operationId()).filter(op -> op.status() == HistoryOperationStatus.PENDING)
                .ifPresent(op -> failLocked(op, "PUBLICATION_FAILED"));
    }

    public List<UUID> due() {
        return jdbc.queryForList("""
                select h.id from dbo.historysyncoperation h where h.deadlineat>clock_timestamp() and
                  ((h.status='RUNNING' and h.leaseuntil<=clock_timestamp()) or
                   (h.status='PENDING' and exists (select 1 from dbo.notificationoutbox o
                    where o.operationid=h.id and o.destination=:topic and o.status='PUBLISHED')))
                order by h.createdat,h.id limit :limit
                """, Map.of("topic", HistorySyncConfiguration.TOPIC, "limit", limits.workers()), UUID.class);
    }

    @Transactional
    public void maintain() {
        var expired = jdbc.query("select * from dbo.historysyncoperation where status in ('PENDING','RUNNING') and deadlineat<=clock_timestamp() order by deadlineat limit 100 for update skip locked",
                Map.of(), this::map);
        for (var operation : expired) failLocked(operation, "DEADLINE_EXCEEDED");
        jdbc.update("""
                delete from dbo.historysyncoperation where id in (select h.id from dbo.historysyncoperation h
                  where h.status in ('SUCCEEDED','FAILED') and h.retainuntil<=clock_timestamp()
                    and not exists(select 1 from dbo.notificationoutbox o where o.operationid=h.id)
                  order by h.retainuntil limit 100 for update skip locked)
                """, Map.of());
        // Terminal command rows retain their identity through the operation's promised retry window.
        jdbc.update("""
                delete from dbo.notificationoutbox where id in (select o.id from dbo.notificationoutbox o
                  join dbo.historysyncoperation h on h.id=o.operationid
                  where h.status in ('SUCCEEDED','FAILED') and h.retainuntil<=clock_timestamp()
                  order by h.retainuntil limit 100 for update of o skip locked)
                """, Map.of());
    }

    @Transactional
    public boolean retryQuarantined(UUID outbox) {
        return jdbc.update("""
                update dbo.notificationoutbox o set status='RETRY',nextattemptat=clock_timestamp(),errorcode=null
                where o.id=:id and o.destination=:topic and o.status='QUARANTINED'
                  and exists(select 1 from dbo.historysyncoperation h where h.id=o.operationid
                    and h.status='PENDING' and h.deadlineat>clock_timestamp())
                """, Map.of("id", outbox, "topic", HistorySyncConfiguration.TOPIC)) == 1;
    }

    public boolean valid(Operation claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from dbo.historysyncoperation where id=:id and status='RUNNING'
                  and claimversion=:version and leaseuntil>clock_timestamp() and deadlineat>clock_timestamp())
                """, Map.of("id", claim.id(), "version", claim.version()), Boolean.class));
    }

    private Optional<Operation> lock(UUID id) {
        return jdbc.query("select * from dbo.historysyncoperation where id=:id for update", Map.of("id", id), this::map).stream().findFirst();
    }
    private void succeed(Operation operation, int importedCount, boolean historyStartReached) {
        int changed = jdbc.update("""
                update dbo.historysyncoperation set status='SUCCEEDED',result=cast(:result as jsonb),errorcode=null,
                  completedat=clock_timestamp(),updatedat=clock_timestamp(),leaseuntil=null,
                  retainuntil=greatest(retainuntil,clock_timestamp()+greatest(retainuntil-deadlineat,
                    :retention*interval '1 millisecond')) where id=:id and status=:status and claimversion=:version
                    and deadlineat>clock_timestamp() and (status='PENDING' or leaseuntil>clock_timestamp())
                """, Map.of("id", operation.id(), "result", mapper.writeValueAsString(new HistorySyncResultDto(importedCount, historyStartReached)),
                "retention", config.historyRetention().toMillis(), "status", operation.status().name(), "version", operation.version()));
        if (changed != 1) throw new StaleClaim();
        events.changed(operation.organization(), operation.initiator(), operation.id());
    }
    private void failLocked(Operation operation, String code) {
        jdbc.update("""
                update dbo.historysyncoperation set status='FAILED',errorcode=:code,completedat=clock_timestamp(),
                  updatedat=clock_timestamp(),leaseuntil=null,retainuntil=greatest(retainuntil,
                  clock_timestamp()+greatest(retainuntil-deadlineat,:retention*interval '1 millisecond'))
                  where id=:id and status in ('PENDING','RUNNING')
                """, Map.of("id", operation.id(), "code", code, "retention", config.historyRetention().toMillis()));
        jdbc.update("""
                update dbo.notificationoutbox set status='QUARANTINED',leaseuntil=null,errorcode=:code,claimversion=claimversion+1
                where operationid=:id and status in ('PENDING','RETRY','IN_FLIGHT')
                """, Map.of("id", operation.id(), "code", code));
        events.changed(operation.organization(), operation.initiator(), operation.id());
    }
    private void checkScope(Operation operation, ConversationEntity conversation) {
        if (!operation.account().equals(conversation.getChannelAccount().getId())
                || !operation.workspace().equals(conversation.getWorkspace().getId())
                || !operation.organization().equals(conversation.getWorkspace().getOrganization().getId()))
            throw new NotificationApiException(403, "ACCESS_REVOKED");
    }
    private UUID organization(AccessSubject subject) {
        if (subject.expired(Instant.now())) throw new NotificationApiException(403, "FORBIDDEN");
        return jdbc.query("select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid where e.employeeid=:actor and u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')",
                Map.of("actor", subject.employeeId()), (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst()
                .orElseThrow(() -> new NotificationApiException(403, "FORBIDDEN"));
    }
    private void requireConversation(AccessSubject subject, UUID conversation) {
        if (!policy.allows(subject, AccessPolicy.Resource.CONVERSATION, conversation)) throw new NotificationApiException(404, "NOT_FOUND");
    }
    private HistorySyncRequest normalize(HistorySyncRequest input) {
        if (input == null || input.limit() < 1 || input.limit() > 100 || (input.beforeExternalId() != null && input.beforeExternalId().length() > 256))
            throw new NotificationApiException(400, "INVALID_REQUEST");
        return new HistorySyncRequest(input.beforeExternalId() == null || input.beforeExternalId().isBlank() ? null : input.beforeExternalId(), input.limit());
    }
    private String key(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new NotificationApiException(400, "INVALID_REQUEST");
        return UUID.fromString(value).toString();
    }
    private String hash(UUID conversation, HistorySyncRequest request) {
        return NotificationIdentity.digest("HISTORY/v1", conversation.toString(), request.beforeExternalId() == null ? "" : request.beforeExternalId(), Integer.toString(request.limit()));
    }
    private Operation map(ResultSet rs, int row) throws SQLException {
        String result = rs.getString("result");
        return new Operation(rs.getObject("id", UUID.class), rs.getObject("organizationid", UUID.class), rs.getObject("workspaceid", UUID.class),
                rs.getObject("conversationid", UUID.class), rs.getObject("channelaccountid", UUID.class), rs.getObject("initiatoremployeeid", UUID.class),
                rs.getString("idempotencykey"), rs.getString("requesthash"), rs.getString("beforeexternalid"), rs.getInt("requestlimit"),
                HistoryOperationStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getLong("claimversion"),
                rs.getTimestamp("leaseuntil") == null ? null : rs.getTimestamp("leaseuntil").toInstant(), rs.getTimestamp("deadlineat").toInstant(),
                rs.getTimestamp("createdat").toInstant(), rs.getTimestamp("updatedat").toInstant(),
                result == null ? null : mapper.readValue(result, HistorySyncResultDto.class), rs.getString("errorcode"));
    }
}
