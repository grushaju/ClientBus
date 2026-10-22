package kit.penny.clientbus.server.notification.outbox;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class UiOutboxStore {
    public record Claim(UUID id, UUID eventId, UUID organizationId, String destination, String type,
                        int schemaVersion, String payload, UUID operationId, long version, int attempts) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties config;
    public UiOutboxStore(NamedParameterJdbcTemplate jdbc, NotificationProperties config) {this.jdbc=jdbc;this.config=config;}

    @Transactional
    public Optional<Claim> claim() {
        var rows=jdbc.query("""
            with candidate as (
                select id from dbo.notification_outbox
                where (status in ('PENDING','RETRY') and next_attempt_at<=clock_timestamp())
                   or (status='IN_FLIGHT' and lease_until<=clock_timestamp())
                order by next_attempt_at,created_at,id limit 1 for update skip locked
            )
            update dbo.notification_outbox o set status='IN_FLIGHT',attempts=o.attempts+1,
                claim_version=o.claim_version+1,lease_until=clock_timestamp()+(:lease * interval '1 millisecond'),error_code=null
            from candidate c where o.id=c.id
            returning o.*
            """,Map.of("lease",config.lease().toMillis()),(rs,n)->new Claim(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getObject("organization_id",UUID.class),rs.getString("destination"),rs.getString("event_type"),
                rs.getInt("schema_version"),rs.getString("payload"),rs.getObject("operation_id",UUID.class),rs.getLong("claim_version"),rs.getInt("attempts")));
        return rows.stream().findFirst();
    }

    public boolean published(Claim claim) {
        return jdbc.update("""
            update dbo.notification_outbox set status='PUBLISHED',published_at=clock_timestamp(),lease_until=null,error_code=null
            where id=:id and status='IN_FLIGHT' and claim_version=:version
            """,Map.of("id",claim.id(),"version",claim.version()))==1;
    }
    public boolean failed(Claim claim, boolean permanent, String code) {
        long delay=Math.min(300_000L,1000L << Math.min(claim.attempts()-1,8));
        return jdbc.update("""
            update dbo.notification_outbox set status=:status,lease_until=null,error_code=:code,
                next_attempt_at=clock_timestamp()+(:delay * interval '1 millisecond')
            where id=:id and status='IN_FLIGHT' and claim_version=:version
            """,Map.of("id",claim.id(),"version",claim.version(),"status",permanent?"QUARANTINED":"RETRY","code",code,"delay",delay))==1;
    }
    /** Administrative service operation; identity/payload are immutable. Commands are excluded until 11.10. */
    public boolean retryQuarantined(UUID id) {
        return jdbc.update("""
            update dbo.notification_outbox set status='RETRY',next_attempt_at=clock_timestamp(),error_code=null
            where id=:id and status='QUARANTINED' and destination=:topic and operation_id is null
            """,Map.of("id",id,"topic",UiKafkaSubscriber.TOPIC))==1;
    }
    public Map<String,Object> statistics() {
        return jdbc.queryForMap("""
            select count(*) filter (where status in ('PENDING','RETRY','IN_FLIGHT')) as pending,
                count(*) filter (where status='QUARANTINED') as quarantined,
                coalesce(extract(epoch from (clock_timestamp()-min(created_at) filter
                    (where status in ('PENDING','RETRY','IN_FLIGHT')))),0) as oldest_seconds
            from dbo.notification_outbox
            """,Map.of());
    }
}
