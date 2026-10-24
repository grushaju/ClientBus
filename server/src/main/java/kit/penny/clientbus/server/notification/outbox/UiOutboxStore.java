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
        return claim(UiKafkaSubscriber.TOPIC, false);
    }

    @Transactional
    public Optional<Claim> claimHistory() {
        return claim(kit.penny.clientbus.server.notification.history.HistorySyncConfiguration.TOPIC, true);
    }

    private Optional<Claim> claim(String destination, boolean command) {
        var rows=jdbc.query("""
            with candidate as (
                select id from dbo.notificationoutbox
                where destination=:destination and (operationid is not null)=:command and
                    ((status in ('PENDING','RETRY') and nextattemptat<=clock_timestamp())
                     or (status='IN_FLIGHT' and leaseuntil<=clock_timestamp()))
                order by nextattemptat,createdat,id limit 1 for update skip locked
            )
            update dbo.notificationoutbox o set status='IN_FLIGHT',attempts=o.attempts+1,
                claimversion=o.claimversion+1,leaseuntil=clock_timestamp()+(:lease * interval '1 millisecond'),errorcode=null
            from candidate c where o.id=c.id
            returning o.*
            """,Map.of("lease",config.lease().toMillis(),"destination",destination,"command",command),(rs,n)->new Claim(rs.getObject("id",UUID.class),rs.getObject("eventid",UUID.class),
                rs.getObject("organizationid",UUID.class),rs.getString("destination"),rs.getString("eventtype"),
                rs.getInt("schemaversion"),rs.getString("payload"),rs.getObject("operationid",UUID.class),rs.getLong("claimversion"),rs.getInt("attempts")));
        return rows.stream().findFirst();
    }

    public boolean published(Claim claim) {
        return jdbc.update("""
            update dbo.notificationoutbox set status='PUBLISHED',publishedat=clock_timestamp(),leaseuntil=null,errorcode=null
            where id=:id and status='IN_FLIGHT' and claimversion=:version
            """,Map.of("id",claim.id(),"version",claim.version()))==1;
    }
    public boolean failed(Claim claim, boolean permanent, String code) {
        long delay=Math.min(300_000L,1000L << Math.min(claim.attempts()-1,8));
        return jdbc.update("""
            update dbo.notificationoutbox set status=:status,leaseuntil=null,errorcode=:code,
                nextattemptat=clock_timestamp()+(:delay * interval '1 millisecond')
            where id=:id and status='IN_FLIGHT' and claimversion=:version
            """,Map.of("id",claim.id(),"version",claim.version(),"status",permanent?"QUARANTINED":"RETRY","code",code,"delay",delay))==1;
    }
    /** Administrative service operation; identity/payload are immutable. Commands are excluded until 11.10. */
    public boolean retryQuarantined(UUID id) {
        return jdbc.update("""
            update dbo.notificationoutbox set status='RETRY',nextattemptat=clock_timestamp(),errorcode=null
            where id=:id and status='QUARANTINED' and destination=:topic and operationid is null
            """,Map.of("id",id,"topic",UiKafkaSubscriber.TOPIC))==1;
    }
    public Map<String,Object> statistics() {
        return jdbc.queryForMap("""
            select count(*) filter (where status in ('PENDING','RETRY','IN_FLIGHT')) as pending,
                count(*) filter (where status='QUARANTINED') as quarantined,
                coalesce(extract(epoch from (clock_timestamp()-min(createdat) filter
                    (where status in ('PENDING','RETRY','IN_FLIGHT')))),0) as oldest_seconds
            from dbo.notificationoutbox
            """,Map.of());
    }
}
