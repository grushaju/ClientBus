package kit.penny.clientbus.server.notification.push;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.center.NotificationCenterService;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import io.micrometer.core.instrument.Gauge;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix="clientbus.notifications",name="push-enabled",havingValue="true")
public class PushDeliveryWorker implements AutoCloseable {
    private final PushDeliveryStore store;private final NotificationCenterService center;private final AccessPolicy access;
    private final PushEndpointCipher cipher;private final PushSettings settings;private final NotificationProperties config;
    private final Map<kit.penny.clientbus.common.notification.PushProviderType,PushProvider> providers;
    private final ThreadPoolExecutor calls;private final MeterRegistry meters;
    private final AtomicBoolean stopped=new AtomicBoolean();
    public PushDeliveryWorker(PushDeliveryStore store,NotificationCenterService center,AccessPolicy access,
            PushEndpointCipher cipher,PushSettings settings,NotificationProperties config,List<PushProvider> providers,MeterRegistry meters){
        this.store=store;this.center=center;this.access=access;this.cipher=cipher;this.settings=settings;this.config=config;this.meters=meters;
        cipher.requireConfigured();
        if(settings.providerTimeout().compareTo(config.lease())>=0)throw new IllegalArgumentException("Push timeout must be shorter than lease");
        var adapters=new EnumMap<kit.penny.clientbus.common.notification.PushProviderType,PushProvider>(kit.penny.clientbus.common.notification.PushProviderType.class);
        for(var provider:providers)if(adapters.put(provider.provider(),provider)!=null)throw new IllegalArgumentException("Duplicate push provider");
        if(settings.allowedApps().isEmpty() || settings.allowedApps().stream().anyMatch(app->!adapters.containsKey(app.provider())))
            throw new IllegalStateException("Push enabled without configured provider adapters");
        this.providers=Map.copyOf(adapters);
        calls=new ThreadPoolExecutor(settings.providerThreads(),settings.providerThreads(),0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{
            var thread=new Thread(r,"notification-push-provider");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("clientbus.notification.push.provider.active",calls,ThreadPoolExecutor::getActiveCount).register(meters);
        Gauge.builder("clientbus.notification.push.provider.capacity",calls,ThreadPoolExecutor::getMaximumPoolSize).register(meters);
        Gauge.builder("clientbus.notification.push.admission.enabled",stopped,state->state.get()?0:1).register(meters);
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.push-poll-ms:1000}",scheduler="pushDeliveryScheduler")
    public void tick(){
        if(stopped.get())return;
        try{for(int index=0;index<config.pushBatchSize() && !stopped.get() && !Thread.currentThread().isInterrupted();index++){
            var claim=store.claim();if(claim.isEmpty())break;deliver(claim.get());
        }}catch(RuntimeException error){
            meters.counter("clientbus.notification.push.errors","stage","database").increment();
            NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER,NotificationDiagnostics.Outcome.FAILED,
                    NotificationDiagnostics.Code.DB_UNAVAILABLE,null,null);
        }
    }
    void deliver(PushDeliveryStore.Claim claim){
        if(stopped.get())return;
        try{
            if(!store.current(claim)){
                meters.counter("clientbus.notification.push.stale").increment();
                NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER,NotificationDiagnostics.Outcome.STALE,
                        NotificationDiagnostics.Code.LEASE_LOST,claim.notificationId(),claim.id());return;
            }
            if(!Instant.now().isBefore(claim.expiresAt())){terminal(claim,"EXPIRED","TTL_EXPIRED");return;}
            var notification=center.findForRecipient(claim.notificationId(),claim.recipientId());
            if(notification.isEmpty() || !notification.get().alertActive() || notification.get().readAt()!=null){terminal(claim,"CANCELLED","ALERT_INACTIVE");return;}
            if(store.endpointChanged(claim)){store.retry(claim,"ENDPOINT_CHANGED",Duration.ZERO);return;}
            if(!access.allowsPush(claim.id())){terminal(claim,"CANCELLED","ACCESS_OR_BINDING_CHANGED");return;}
            if(!settings.allows(claim.appId(),claim.provider(),claim.environment(),claim.platform())){terminal(claim,"FAILED","INVALID_CONFIGURATION");return;}
            var provider=providers.get(claim.provider());
            if(provider==null){terminal(claim,"FAILED","PROVIDER_UNAVAILABLE");return;}
            String endpoint=cipher.decrypt(claim.encryptedEndpoint());
            var ttl=Duration.between(Instant.now(),claim.expiresAt());
            if(ttl.isNegative() || ttl.isZero()){terminal(claim,"EXPIRED","TTL_EXPIRED");return;}
            var request=new PushProvider.Request(claim.notificationId(),1,claim.appId(),claim.environment(),claim.platform(),endpoint,ttl);
            Future<PushProvider.Result> pending;
            try{pending=calls.submit(()->send(provider,request));}
            catch(RejectedExecutionException busy){waitOutcome(claim,"busy",NotificationDiagnostics.Code.QUEUE_FULL);store.retry(claim,"PROVIDER_BUSY",Duration.ZERO);return;}
            PushProvider.Result result;
            try{result=pending.get(settings.providerTimeout().toMillis(),TimeUnit.MILLISECONDS);}
            catch(TimeoutException timeout){pending.cancel(true);waitOutcome(claim,"timeout",NotificationDiagnostics.Code.PROVIDER_TIMEOUT);store.retry(claim,"PROVIDER_TIMEOUT",Duration.ZERO);return;}
            catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();waitOutcome(claim,"interrupted",NotificationDiagnostics.Code.SHUTDOWN);store.retry(claim,"INTERRUPTED",Duration.ZERO);return;}
            catch(ExecutionException failure){waitOutcome(claim,"failure",NotificationDiagnostics.Code.PROVIDER_UNAVAILABLE);store.retry(claim,"PROVIDER_FAILURE",Duration.ZERO);return;}
            meters.counter("clientbus.notification.push.provider.waits","result","completed").increment();
            if(result==null || result.outcome()==null){terminal(claim,"FAILED","INVALID_PROVIDER_RESULT");return;}
            switch(result.outcome()){
                case ACCEPTED->store.terminal(claim,"PROVIDER_ACCEPTED",null,result.providerMessageId());
                case INVALID_ENDPOINT->store.invalidEndpoint(claim);
                case RETRYABLE->store.retry(claim,"PROVIDER_RETRY",result.retryAfter());
                case INVALID_PAYLOAD->terminal(claim,"FAILED","INVALID_PAYLOAD");
                case REJECTED->terminal(claim,"FAILED","PROVIDER_REJECTED");
            }
        }catch(PushEndpointCipher.Unavailable invalid){terminal(claim,"FAILED","ENDPOINT_UNAVAILABLE");}
        catch(RuntimeException unavailable){
            meters.counter("clientbus.notification.push.errors","stage","processing").increment();
            NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER,NotificationDiagnostics.Outcome.RETRY,
                    NotificationDiagnostics.Code.INTERNAL_ERROR,claim.notificationId(),claim.id());
            store.retry(claim,"POLICY_OR_DATABASE_UNAVAILABLE",Duration.ZERO);
        }
    }
    private PushProvider.Result send(PushProvider provider,PushProvider.Request request){
        meters.counter("clientbus.notification.push.provider.calls").increment();
        long started=System.nanoTime();
        try{
            var result=provider.send(request);
            String outcome=result==null || result.outcome()==null?"invalid":result.outcome().name().toLowerCase(Locale.ROOT);
            meters.counter("clientbus.notification.push.provider.responses","outcome",outcome).increment();
            return result;
        }catch(RuntimeException | Error failure){
            meters.counter("clientbus.notification.push.provider.responses","outcome","exception").increment();throw failure;
        }finally{
            meters.timer("clientbus.notification.push.provider.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
        }
    }
    private void waitOutcome(PushDeliveryStore.Claim claim,String result,NotificationDiagnostics.Code code){
        meters.counter("clientbus.notification.push.provider.waits","result",result).increment();
        NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER,NotificationDiagnostics.Outcome.RETRY,code,
                claim.notificationId(),claim.id());
    }
    private void terminal(PushDeliveryStore.Claim claim,String status,String code){store.terminal(claim,status,code,null);}
    @Override @PreDestroy public void close(){if(stopped.compareAndSet(false,true))calls.shutdownNow();}
}
