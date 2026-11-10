package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Only validated immutable journal text is cached. Each caller parses its own result graph. */
final class DikidiDayCache implements AutoCloseable {
    record Key(UUID project,UUID connection,long credentials,String external,LocalDate day,long context,long zone,long epoch) {}
    record Snapshot(String body,Instant observedAt,long sequence,long epoch,LocalDate day,CrmSourceHealth health) {
        Snapshot(String body,Instant observedAt,long sequence,long epoch,LocalDate day){this(body,observedAt,sequence,epoch,day,null);}
    }
    record Result(Snapshot snapshot,boolean hit) {}
    private record Entry(Snapshot value,Instant expires,int bytes,Flight origin) {}
    private static final class Flight {
        final CompletableFuture<Snapshot> result=new CompletableFuture<>();
        final Instant started;
        volatile boolean valid=true;
        int waiters;
        Flight(Instant started){this.started=started;}
    }
    private final Map<Key,Entry> cache=new LinkedHashMap<>(128,.75f,true);
    private final Map<Key,Flight> flights=new HashMap<>();
    private long weight,hits,loads,joins;
    private boolean closed;
    record Diagnostics(long hits,long loads,long joins,int entries,long bytes) {}
    synchronized Diagnostics diagnostics(){return new Diagnostics(hits,loads,joins,cache.size(),weight);}
    private final Duration ttl;
    private final int maxEntries,maxBytes,maxEntryBytes;
    private final Clock clock;
    private final boolean reuse;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
        var thread=new Thread(task,"dikidi-journal");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    DikidiDayCache(Duration ttl,int maxEntries,int maxBytes,int maxEntryBytes){this(ttl,maxEntries,maxBytes,maxEntryBytes,Clock.systemUTC());}
    DikidiDayCache(Duration ttl,int maxEntries,int maxBytes,int maxEntryBytes,Clock clock){this(ttl,maxEntries,maxBytes,maxEntryBytes,clock,true);}
    DikidiDayCache(Duration ttl,int maxEntries,int maxBytes,int maxEntryBytes,Clock clock,boolean reuse){
        if(ttl==null||ttl.isNegative()||ttl.isZero()||maxEntries<1||maxBytes<1024||maxEntryBytes<1024||maxEntryBytes>maxBytes)
            throw new IllegalArgumentException("Invalid journal cache settings");
        this.ttl=ttl;this.maxEntries=maxEntries;this.maxBytes=maxBytes;this.maxEntryBytes=maxEntryBytes;this.clock=clock;this.reuse=reuse;
    }

    Result read(Key key,CrmAccess caller,CrmJournalOptions options,Function<CrmAccess,Snapshot> load,BooleanSupplier current){
        checkDeadline(options);
        Entry entry;
        synchronized(this){
            requireOpen();entry=reuse&&!options.force()?cache.get(key):null;
            if(entry!=null&&!entry.expires().isAfter(clock.instant())){cache.remove(key);weight-=entry.bytes();entry=null;}
        }
        // This supplier may access the database. Never run it while holding the cache monitor.
        if(!current.getAsBoolean())throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
        checkDeadline(options);
        if(entry!=null){
            synchronized(this){
                requireOpen();checkDeadline(options);
                if(!entry.origin().valid)throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                if(entry.expires().isAfter(clock.instant())){hits++;return new Result(entry.value(),true);}
            }
        }
        Flight flight;
        CrmAccess owned=null;
        synchronized(this){
            requireOpen();checkDeadline(options);
            // Another loader may have published while this caller checked its context.
            var ready=reuse&&!options.force()?cache.get(key):null;
            if(ready!=null&&ready.origin().valid&&ready.expires().isAfter(clock.instant())){
                hits++;return new Result(ready.value(),true);
            }
            flight=flights.get(key);
            if(flight!=null&&options.force()&&flight.started.isBefore(options.barrier()))throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY);
            if(flight==null){
                if(flights.size()>=2)throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY);
                char[] secret=caller.secret();
                try{owned=new CrmAccess(caller.connectionId(),caller.credentialVersion(),caller.login(),secret);}finally{Arrays.fill(secret,'\0');}
                flight=new Flight(clock.instant());flights.put(key,flight);loads++;
            }else joins++;
            if(flight.waiters>=64)throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY);
            flight.waiters++;
        }
        if(owned!=null)start(key,flight,owned,load,current);
        try{
            checkDeadline(options);
            var snapshot=flight.result.get(Math.max(1,Duration.between(clock.instant(),options.deadline()).toMillis()),TimeUnit.MILLISECONDS);
            if(!current.getAsBoolean()||!flight.valid)throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
            checkDeadline(options);
            return new Result(snapshot,false);
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
        }catch(TimeoutException error){throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
        }catch(ExecutionException error){
            if(error.getCause() instanceof CrmFailure failure)throw failure;
            throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
        }finally{synchronized(this){flight.waiters--;}}
    }

    private void start(Key key,Flight flight,CrmAccess owned,Function<CrmAccess,Snapshot> load,BooleanSupplier current){
        try{
            executor.execute(()->{
                try(owned){
                    if(!flight.valid)throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                    var snapshot=load.apply(owned);
                    boolean accepted=current.getAsBoolean();
                    int bytes=reuse?snapshot.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length:0;
                    synchronized(this){
                        if(!accepted||!flight.valid||closed||flights.get(key)!=flight)throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                        if(reuse&&bytes<=maxEntryBytes){
                            var previous=cache.put(key,new Entry(snapshot,snapshot.observedAt().plus(ttl),bytes,flight));
                            if(previous!=null)weight-=previous.bytes();weight+=bytes;
                            var iterator=cache.entrySet().iterator();
                            while((cache.size()>maxEntries||weight>maxBytes)&&iterator.hasNext()){
                                var evicted=iterator.next();weight-=evicted.getValue().bytes();iterator.remove();
                            }
                        }
                        flights.remove(key,flight);
                        flight.result.complete(snapshot);
                    }
                }catch(Throwable error){flight.result.completeExceptionally(error);
                }finally{synchronized(this){flights.remove(key,flight);}}
            });
        }catch(RejectedExecutionException full){
            owned.close();synchronized(this){flights.remove(key,flight);}
            flight.result.completeExceptionally(new CrmFailure(CrmFailure.Code.CONNECTION_BUSY));
        }
    }
    private void checkDeadline(CrmJournalOptions options){
        if(Thread.currentThread().isInterrupted()||!options.deadline().isAfter(clock.instant()))throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
    }
    private void requireOpen(){if(closed)throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);}
    synchronized void invalidate(UUID connection){
        var iterator=cache.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();
            if(entry.getKey().connection().equals(connection)){entry.getValue().origin().valid=false;weight-=entry.getValue().bytes();iterator.remove();}
        }
        for(var entry:flights.entrySet())if(entry.getKey().connection().equals(connection)){
            entry.getValue().valid=false;entry.getValue().result.completeExceptionally(new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH));
        }
    }
    public void close(){
        synchronized(this){
            closed=true;for(var entry:cache.values())entry.origin().valid=false;
            cache.clear();weight=0;
            for(var flight:flights.values()){
                flight.valid=false;flight.result.completeExceptionally(new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE));
            }
            flights.clear();
        }
        executor.shutdownNow();
    }
}
