package kit.penny.clientbus.server.service;

import kit.penny.clientbus.server.notification.contract.NotificationIdentity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

/** One bounded correlation journal, not a send queue: external I/O never runs here. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class OutboundCallbackStore {
    public record Result(UUID messageId, UUID attemptId, String outcome, String finalExternalId) {}
    private final NamedParameterJdbcTemplate jdbc;
    public OutboundCallbackStore(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    private Map<String,Object> key(UUID account,String recipient,String pending) {
        if(account==null || recipient==null || recipient.isBlank() || recipient.length()>256
                || pending==null || pending.isBlank() || pending.length()>256)
            throw new IllegalArgumentException("Invalid external correlation");
        return new HashMap<>(Map.of("account",account,"recipient",recipient,"pending",pending));
    }
    private Result lock(Map<String,Object> p,boolean knownAttempt) {
        p.put("key",NotificationIdentity.digest("send-result/v1",p.get("account").toString(),
                p.get("recipient").toString(),p.get("pending").toString()));
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key,0))",p,rs->{});
        var rows=read(p);
        if(!rows.isEmpty()) return rows.getFirst();
        // Bound unknown callbacks as well as their retention. Known sends cannot be evicted by noise.
        if(!knownAttempt) {
            jdbc.query("select pg_advisory_xact_lock(84011831)",Map.of(),rs->{});
            Long count=jdbc.queryForObject("select count(*) from dbo.outboundsendcallback where messageid is null",Map.of(),Long.class);
            if(count!=null && count>=4096) return null;
        }
        p.put("id",UUID.randomUUID());
        jdbc.update("""
                insert into dbo.outboundsendcallback(id,channelaccountid,recipientexternalid,pendingexternalid,createdat,retainuntil)
                values(:id,:account,:recipient,:pending,clock_timestamp(),clock_timestamp()+interval '7 days')
                """,p);
        return new Result(null,null,null,null);
    }
    private List<Result> read(Map<String,Object> p) {
        return jdbc.query("""
                select messageid,sendattemptid,outcome,finalexternalid from dbo.outboundsendcallback
                where channelaccountid=:account and recipientexternalid=:recipient and pendingexternalid=:pending for update
                """,p,(rs,n)->new Result(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)));
    }
    public Result bind(UUID account,String recipient,String pending,UUID message,UUID attempt) {
        var p=key(account,recipient,pending); var row=lock(p,true);
        if(row.messageId()!=null && (!row.messageId().equals(message) || !Objects.equals(row.attemptId(),attempt)))
            throw new IllegalStateException("External identity is bound to another send attempt");
        p.put("message",Objects.requireNonNull(message));p.put("attempt",attempt);
        jdbc.update("""
                update dbo.outboundsendcallback set messageid=:message,sendattemptid=:attempt
                where channelaccountid=:account and recipientexternalid=:recipient and pendingexternalid=:pending
                """,p);
        return new Result(message,attempt,row.outcome(),row.finalExternalId());
    }
    public Result record(UUID account,String recipient,String pending,String finalId,boolean failed) {
        var p=key(account,recipient,pending); var row=lock(p,false);
        if(row==null) return null;
        if(finalId==null || finalId.isBlank() || finalId.length()>256) throw new IllegalArgumentException("Invalid external result");
        String outcome=failed || "FAILED".equals(row.outcome()) ? "FAILED" : "SENT";
        p.put("outcome",outcome);p.put("final",finalId);
        jdbc.update("""
                update dbo.outboundsendcallback set outcome=:outcome,finalexternalid=:final
                where channelaccountid=:account and recipientexternalid=:recipient and pendingexternalid=:pending
                """,p);
        return new Result(row.messageId(),row.attemptId(),outcome,finalId);
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.callback-cleanup-ms:60000}")
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void cleanup() {
        jdbc.update("delete from dbo.outboundsendcallback where id in (select id from dbo.outboundsendcallback where retainuntil<clock_timestamp() order by retainuntil limit 256 for update skip locked)",Map.of());
    }
}
