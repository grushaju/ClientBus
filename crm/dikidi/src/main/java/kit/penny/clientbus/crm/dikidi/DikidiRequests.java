package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Fail-fast admission: no external pause or token wait holds a session/writer lease. */
public final class DikidiRequests {
    public record Settings(double perSecond,int burst,int maxConnections,Duration fallback,Duration maxFallback,double globalPerSecond,int globalBurst) {
        public Settings(double perSecond,int burst,int maxConnections,Duration fallback,Duration maxFallback){
            this(perSecond,burst,maxConnections,fallback,maxFallback,perSecond*4,(int)Math.min(Integer.MAX_VALUE,burst*4L));
        }
        public Settings {
            if(!Double.isFinite(perSecond)||perSecond<=0||burst<1||maxConnections<1||fallback==null||fallback.isNegative()||fallback.isZero()
                    ||maxFallback==null||maxFallback.compareTo(fallback)<0||maxFallback.compareTo(Duration.ofDays(1))>0
                    ||!Double.isFinite(globalPerSecond)||globalPerSecond<=0||globalBurst<1)throw new IllegalArgumentException("Invalid DIKIDI traffic settings");
        }
        public static Settings defaults(){return new Settings(4,32,1024,Duration.ofSeconds(30),Duration.ofMinutes(15));}
    }
    private static final class Bucket { double tokens;Instant updated;Bucket(double tokens,Instant now){this.tokens=tokens;updated=now;} }
    private final Settings settings;private final CrmTrafficPort pauses;private final Clock clock;
    private final Map<UUID,Bucket> buckets=new HashMap<>();
    private final Bucket global;
    private final LongAdder sent=new LongAdder(),limited=new LongAdder(),paused=new LongAdder(),busy=new LongAdder();
    public record Diagnostics(long sent,long external429,long cooldownRefusals,long localRefusals) {}
    public DikidiRequests(Settings settings,CrmTrafficPort pauses){this(settings,pauses,Clock.systemUTC());}
    public DikidiRequests(Settings settings,CrmTrafficPort pauses,Clock clock){this.settings=settings;this.pauses=pauses;this.clock=clock;this.global=new Bucket(settings.globalBurst(),clock.instant());}
    public static CrmTrafficPort memory(){return new CrmTrafficPort(){
        final Map<UUID,Pause> values=new ConcurrentHashMap<>();
        public Pause current(UUID id){return values.getOrDefault(id,Pause.NONE);}
        public Pause block(UUID id,Instant until){return values.compute(id,(key,old)->new Pause(old!=null&&old.notBefore().isAfter(until)?old.notBefore():until,old==null?1:Math.min(30,old.failures()+1)));}
    };}
    public Instant retryAt(UUID id){var until=pauses.current(id).notBefore();return until!=null&&until.isAfter(clock.instant())?until:null;}
    public void check(UUID id){var until=retryAt(id);if(until!=null){paused.increment();throw new CrmFailure(CrmFailure.Code.COOLDOWN,until,true);}}
    public void acquire(UUID id){
        check(id);var now=clock.instant();
        synchronized(buckets){
            var bucket=buckets.get(id);
            if(bucket==null){
                if(buckets.size()>=settings.maxConnections())buckets.entrySet().removeIf(entry->entry.getValue().updated.plus(Duration.ofMinutes(20)).isBefore(now));
                if(buckets.size()>=settings.maxConnections()){busy.increment();throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY,now.plusSeconds(1),true);}
                bucket=new Bucket(settings.burst(),now);buckets.put(id,bucket);
            }
            bucket.tokens=Math.min(settings.burst(),bucket.tokens+Math.max(0,Duration.between(bucket.updated,now).toNanos()/1e9)*settings.perSecond());bucket.updated=now;
            global.tokens=Math.min(settings.globalBurst(),global.tokens+Math.max(0,Duration.between(global.updated,now).toNanos()/1e9)*settings.globalPerSecond());global.updated=now;
            if(global.tokens<1){busy.increment();throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY,now.plusMillis(Math.max(1,(long)Math.ceil((1-global.tokens)*1000/settings.globalPerSecond()))),true);}
            if(bucket.tokens<1){busy.increment();throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY,now.plusMillis(Math.max(1,(long)Math.ceil((1-bucket.tokens)*1000/settings.perSecond()))),true);}
            bucket.tokens--;global.tokens--;
        }
    }
    public void dispatched(){sent.increment();}
    public void response(UUID id,DikidiTransport.Response response){
        if(response.status()!=429)return;limited.increment();var now=clock.instant();Instant until=parseRetryAfter(response.retryAfter(),now);
        if(until==null){int failures=pauses.current(id).failures();long base=Math.min(settings.maxFallback().toMillis(),settings.fallback().toMillis()*(1L<<Math.min(15,failures)));
            until=now.plusMillis(Math.min(settings.maxFallback().toMillis(),base+java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,base/10))));}
        var saved=pauses.block(id,until);throw new CrmFailure(CrmFailure.Code.RATE_LIMITED,saved.notBefore(),false);
    }
    static Instant parseRetryAfter(String value,Instant now){
        if(value==null||value.length()>128)return null;
        try{Instant result=value.trim().matches("[0-9]+")?now.plusSeconds(Long.parseLong(value.trim())):ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return result.isAfter(now)?result:now.plusSeconds(1);
        }catch(RuntimeException ignored){return null;}
    }
    public Diagnostics diagnostics(){return new Diagnostics(sent.sum(),limited.sum(),paused.sum(),busy.sum());}
}
