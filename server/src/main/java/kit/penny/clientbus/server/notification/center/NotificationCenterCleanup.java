package kit.penny.clientbus.server.notification.center;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.push.PushDeliveryStore;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationCenterCleanup {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties config;
    private final PushDeliveryStore deliveries;
    private final PersonalNotificationEvents events;

    public NotificationCenterCleanup(NamedParameterJdbcTemplate jdbc, NotificationProperties config,
            PushDeliveryStore deliveries, PersonalNotificationEvents events) {
        this.jdbc = jdbc; this.config = config; this.deliveries = deliveries; this.events = events;
    }

    /** One bounded batch per table. Persistence cleanup does not depend on creation/transport/push flags. */
    @Transactional
    public int cleanBatch(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Cleanup batch must be within 1..1000");
        Instant now = jdbc.queryForObject("select clock_timestamp()", Map.of(), Timestamp.class).toInstant();
        var params = Map.<String, Object>of("now", Timestamp.from(now), "limit", limit,
                "publishedBefore", Timestamp.from(now.minus(config.publishedOutboxRetention())));
        int cleaned = deliveries.cleanupBatch(now, limit);
        var removed = jdbc.query("""
                with candidate as (select n.id from dbo.notification n where n.retainuntil<=:now
                    and not exists(select 1 from dbo.notificationdelivery d where d.notificationid=n.id)
                    order by n.retainuntil,n.id limit :limit for update skip locked)
                delete from dbo.notification n using candidate c where n.id=c.id returning n.organizationid,n.recipientemployeeid
                """, params, (rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        removed.stream().distinct().forEach(row -> events.publish(row.getKey(), row.getValue(), null, false));
        cleaned += removed.size();
        cleaned += jdbc.update("""
                with candidate as (select d.id from dbo.notificationdedup d where d.dedupuntil<=:now
                    and not exists(select 1 from dbo.notification n where n.dedupid=d.id)
                    order by d.dedupuntil,d.id limit :limit for update skip locked)
                delete from dbo.notificationdedup d using candidate c where d.id=c.id
                """, params);
        cleaned += jdbc.update("""
                with candidate as (select o.id from dbo.notificationoccurrence o where o.dedupuntil<=:now
                    and not exists(select 1 from dbo.notificationdedup d where d.occurrenceid=o.id)
                    order by o.dedupuntil,o.id limit :limit for update skip locked)
                delete from dbo.notificationoccurrence o using candidate c where o.id=c.id
                """, params);
        cleaned += jdbc.update("""
                with candidate as (select id from dbo.notificationcommand where retainuntil<=:now
                    order by retainuntil,id limit :limit for update skip locked)
                delete from dbo.notificationcommand o using candidate c where o.id=c.id
                """, params);
        cleaned += jdbc.update("""
                with candidate as (select id from dbo.notificationoutbox where status='PUBLISHED' and publishedat<=:publishedBefore
                    order by publishedat,id limit :limit for update skip locked)
                delete from dbo.notificationoutbox o using candidate c where o.id=c.id
                """, params);
        return cleaned;
    }
}
