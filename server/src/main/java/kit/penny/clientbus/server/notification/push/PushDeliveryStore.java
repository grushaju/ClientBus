package kit.penny.clientbus.server.notification.push;

import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class PushDeliveryStore {
    private record Endpoint(long bindingVersion,long endpointVersion,String fingerprint,boolean enabled) {}
    public record Claim(UUID id,UUID notificationId,UUID installationId,UUID recipientId,long bindingVersion,
                        long endpointVersion,long claimVersion,int attempts,Instant expiresAt,
                        PushProviderType provider,String appId,PushEnvironment environment,PushPlatform platform,
                        byte[] encryptedEndpoint,String fingerprint) {
        @Override public String toString(){return "PushDelivery.Claim[id="+id+", endpoint=REDACTED]";}
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties config;
    public PushDeliveryStore(NamedParameterJdbcTemplate jdbc,NotificationProperties config){this.jdbc=jdbc;this.config=config;}
    @Transactional
    public Optional<Claim> claim(){
        return jdbc.query("""
            with candidate as (
                select d.id from dbo.notificationdelivery d
                where (d.status in ('PENDING','RETRY') and d.nextattemptat<=clock_timestamp())
                    or (d.status='IN_FLIGHT' and d.leaseuntil<=clock_timestamp())
                order by d.nextattemptat,d.createdat,d.id limit 1 for update skip locked
            ), claimed as (
                update dbo.notificationdelivery d set status='IN_FLIGHT',attempts=d.attempts+1,
                    claimversion=d.claimversion+1,leaseuntil=clock_timestamp()+(:lease * interval '1 millisecond'),
                    attemptendpointversion=i.endpointversion,errorcode=null
                from candidate c,dbo.pushinstallation i where d.id=c.id and i.id=d.registrationid
                returning d.*
            )
            select d.*,n.recipientemployeeid,i.endpointversion,i.provider,i.appid,i.environment,i.platform,
                i.encryptedendpoint,i.endpointfingerprint
            from claimed d join dbo.pushinstallation i on i.id=d.registrationid
                join dbo.notification n on n.id=d.notificationid
            """,Map.of("lease",config.lease().toMillis()),(rs,row)->new Claim(rs.getObject("id",UUID.class),
                rs.getObject("notificationid",UUID.class),rs.getObject("registrationid",UUID.class),rs.getObject("recipientemployeeid",UUID.class),
                rs.getLong("bindingversion"),rs.getLong("endpointversion"),rs.getLong("claimversion"),rs.getInt("attempts"),
                rs.getTimestamp("expiresat").toInstant(),PushProviderType.valueOf(rs.getString("provider")),rs.getString("appid"),
                PushEnvironment.valueOf(rs.getString("environment")),PushPlatform.valueOf(rs.getString("platform")),
                rs.getBytes("encryptedendpoint"),rs.getString("endpointfingerprint"))).stream().findFirst();
    }
    public boolean current(Claim claim){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from dbo.notificationdelivery where id=:id and status='IN_FLIGHT'
                and claimversion=:version and leaseuntil>clock_timestamp())
            """,parameters(claim),Boolean.class));
    }
    public boolean endpointChanged(Claim claim){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from dbo.pushinstallation where id=:installation
                and bindingversion=:binding and endpointversion<>:endpoint)
            """,parameters(claim),Boolean.class));
    }
    @Transactional
    public boolean terminal(Claim claim,String status,String code,String providerMessageId){
        if(!Set.of("PROVIDER_ACCEPTED","FAILED","CANCELLED","EXPIRED").contains(status))throw new IllegalArgumentException("Invalid terminal state");
        var parameters=parameters(claim).addValue("status",status).addValue("code",code)
                .addValue("message",providerMessageId==null?null:providerMessageId.substring(0,Math.min(512,providerMessageId.length())))
                .addValue("retention",config.deliveryRetention().toMillis());
        return jdbc.update("""
            update dbo.notificationdelivery set status=:status,errorcode=:code,providermessageid=:message,
                leaseuntil=null,completedat=clock_timestamp(),retainuntil=clock_timestamp()+(:retention * interval '1 millisecond')
            where id=:id and status='IN_FLIGHT' and claimversion=:version
            """,parameters)==1;
    }
    @Transactional
    public boolean retry(Claim claim,String code,Duration hint){
        long remaining=Math.max(0,Duration.between(Instant.now(),claim.expiresAt()).toMillis());
        long delay=retryDelayMillis(claim.attempts(),hint,java.util.concurrent.ThreadLocalRandom.current().nextDouble(),remaining);
        var parameters=parameters(claim).addValue("delay",delay).addValue("expire",delay>=remaining).addValue("code",code)
                .addValue("retention",config.deliveryRetention().toMillis());
        return jdbc.update("""
            update dbo.notificationdelivery set
                status=case when :expire or clock_timestamp()+(:delay * interval '1 millisecond')>=expiresat then 'EXPIRED' else 'RETRY' end,
                errorcode=:code,leaseuntil=null,nextattemptat=clock_timestamp()+(:delay * interval '1 millisecond'),
                completedat=case when :expire or clock_timestamp()+(:delay * interval '1 millisecond')>=expiresat then clock_timestamp() else null end,
                retainuntil=case when :expire or clock_timestamp()+(:delay * interval '1 millisecond')>=expiresat
                    then clock_timestamp()+(:retention * interval '1 millisecond') else null end
            where id=:id and status='IN_FLIGHT' and claimversion=:version
            """,parameters)==1;
    }
    /** Jitter applies to backoff only; a provider minimum is preserved until it would exhaust the original TTL. */
    static long retryDelayMillis(int attempts,Duration hint,double entropy,long remainingMillis){
        if(entropy<0 || entropy>1 || !Double.isFinite(entropy) || remainingMillis<0)
            throw new IllegalArgumentException("Invalid retry delay input");
        long base=Math.min(60_000,1000L << Math.min(Math.max(attempts-1,0),6));
        long delay=Math.min(60_000,Math.round(base*(0.8+0.4*entropy)));
        if(hint!=null && !hint.isNegative()){
            // Clamp before conversion: even an untrusted, enormous hint cannot overflow SQL interval arithmetic.
            long hinted=hint.compareTo(Duration.ofMillis(remainingMillis))>=0?remainingMillis:
                    hint.toMillis()+(hint.getNano()%1_000_000==0?0:1);
            delay=Math.max(delay,hinted);
        }
        return Math.min(delay,remainingMillis);
    }
    @Transactional
    public boolean invalidEndpoint(Claim claim){
        // Registration mutations use installation -> delivery. Lock in that order before applying a provider result.
        var endpoints=jdbc.query("""
            select bindingversion,endpointversion,endpointfingerprint,enabled from dbo.pushinstallation
            where id=:installation for update
            """,parameters(claim),(rs,row)->new Endpoint(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getBoolean(4)));
        if(endpoints.isEmpty())return false;
        var endpoint=endpoints.getFirst();
        if(endpoint.bindingVersion()!=claim.bindingVersion() || !endpoint.enabled())
            return terminal(claim,"CANCELLED","REGISTRATION_CHANGED",null);
        if(endpoint.endpointVersion()!=claim.endpointVersion() || !endpoint.fingerprint().equals(claim.fingerprint()))
            return retry(claim,"ENDPOINT_CHANGED",Duration.ZERO);
        if(!terminal(claim,"FAILED","INVALID_ENDPOINT",null))return false;
        jdbc.update("""
            update dbo.pushinstallation set enabled=false,activeendpointfingerprint=null,registrationversion=registrationversion+1
            where id=:installation and bindingversion=:binding and endpointversion=:endpoint
                and endpointfingerprint=:fingerprint and enabled
            """,parameters(claim).addValue("fingerprint",claim.fingerprint()));
        cancelObsolete(claim.installationId());
        return true;
    }
    /** Rebind/logout/permission changes fence previous in-flight work in the same transaction. */
    public int cancelObsolete(UUID installationId){
        return jdbc.update("""
            update dbo.notificationdelivery d set status='CANCELLED',claimversion=d.claimversion+1,
                leaseuntil=null,errorcode='REGISTRATION_CHANGED',completedat=clock_timestamp(),
                retainuntil=clock_timestamp()+(:retention * interval '1 millisecond')
            from dbo.pushinstallation i where d.registrationid=i.id and i.id=:id
                and d.status in ('PENDING','RETRY','IN_FLIGHT')
                and (d.bindingversion<>i.bindingversion or not i.enabled or i.reportedpermission='DENIED')
            """,Map.of("id",installationId,"retention",config.deliveryRetention().toMillis()));
    }
    @Transactional
    public int cleanupBatch(Instant now,int limit){
        if(limit<1 || limit>1000)throw new IllegalArgumentException("Invalid cleanup batch");
        var p=new MapSqlParameterSource().addValue("now",java.sql.Timestamp.from(now)).addValue("limit",limit)
                .addValue("retention",config.deliveryRetention().toMillis());
        // Complete installation mutations before taking any delivery locks, as in registration/result handling.
        // A registration still referenced by this batch's deliveries is removed by a later batch.
        jdbc.update("""
            update dbo.pushinstallation set enabled=false,activeendpointfingerprint=null,registrationversion=registrationversion+1
            where id in (select id from dbo.pushinstallation where enabled and activeuntil<=:now
                order by activeuntil,id limit :limit for update skip locked)
            """,p);
        int removed=jdbc.update("""
            delete from dbo.pushinstallation i where i.id in (select x.id from dbo.pushinstallation x
                where not x.enabled and x.activeuntil<=:now and not exists
                    (select 1 from dbo.notificationdelivery d where d.registrationid=x.id)
                order by x.activeuntil,x.id limit :limit for update skip locked)
            """,p);
        jdbc.update("""
            update dbo.notificationdelivery set status='EXPIRED',claimversion=claimversion+1,leaseuntil=null,
                completedat=clock_timestamp(),retainuntil=clock_timestamp()+(:retention * interval '1 millisecond'),errorcode='TTL_EXPIRED'
            where id in (select id from dbo.notificationdelivery where status in ('PENDING','RETRY','IN_FLIGHT')
                and expiresat<=:now order by expiresat,id limit :limit for update skip locked)
            """,p);
        removed+=jdbc.update("""
            delete from dbo.notificationdelivery where id in (select id from dbo.notificationdelivery
                where status in ('PROVIDER_ACCEPTED','FAILED','CANCELLED','EXPIRED') and retainuntil<=:now
                order by retainuntil,id limit :limit for update skip locked)
            """,p);
        return removed;
    }
    public Map<String,Object> statistics(){return jdbc.queryForMap("""
        select count(*) filter(where status in ('PENDING','RETRY','IN_FLIGHT')) as pending,
            count(*) filter(where status='IN_FLIGHT') as in_flight,
            coalesce(extract(epoch from(clock_timestamp()-min(createdat) filter(where status in ('PENDING','RETRY','IN_FLIGHT')))),0) as oldest_seconds
        from dbo.notificationdelivery
        """,Map.of());}
    private MapSqlParameterSource parameters(Claim claim){return new MapSqlParameterSource().addValue("id",claim.id())
        .addValue("version",claim.claimVersion()).addValue("installation",claim.installationId())
        .addValue("binding",claim.bindingVersion()).addValue("endpoint",claim.endpointVersion());}
}
