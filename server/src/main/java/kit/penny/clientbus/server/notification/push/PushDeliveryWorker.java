package kit.penny.clientbus.server.notification.push;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.center.NotificationCenterService;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(prefix="clientbus.notifications",name="push-enabled",havingValue="true")
public class PushDeliveryWorker implements AutoCloseable {
    private final PushDeliveryStore store;private final NotificationCenterService center;private final AccessPolicy access;
    private final PushEndpointCipher cipher;private final PushSettings settings;private final NotificationProperties config;
    private final Map<kit.penny.clientbus.common.notification.PushProviderType,PushProvider> providers;
    private final ThreadPoolExecutor calls;private final MeterRegistry meters;
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
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.push-poll-ms:1000}",scheduler="pushDeliveryScheduler")
    public void tick(){
        try{for(int index=0;index<config.pushBatchSize() && !Thread.currentThread().isInterrupted();index++){
            var claim=store.claim();if(claim.isEmpty())break;deliver(claim.get());
        }}catch(RuntimeException error){meters.counter("clientbus.notification.push.errors","stage","database").increment();}
    }
    void deliver(PushDeliveryStore.Claim claim){
        try{
            if(!store.current(claim))return;
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
            try{pending=calls.submit(()->provider.send(request));}
            catch(RejectedExecutionException busy){store.retry(claim,"PROVIDER_BUSY",Duration.ZERO);return;}
            PushProvider.Result result;
            try{result=pending.get(settings.providerTimeout().toMillis(),TimeUnit.MILLISECONDS);}
            catch(TimeoutException timeout){pending.cancel(true);store.retry(claim,"PROVIDER_TIMEOUT",Duration.ZERO);return;}
            catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();store.retry(claim,"INTERRUPTED",Duration.ZERO);return;}
            catch(ExecutionException failure){store.retry(claim,"PROVIDER_FAILURE",Duration.ZERO);return;}
            if(result==null || result.outcome()==null){terminal(claim,"FAILED","INVALID_PROVIDER_RESULT");return;}
            switch(result.outcome()){
                case ACCEPTED->{if(store.terminal(claim,"PROVIDER_ACCEPTED",null,result.providerMessageId()))outcome("accepted");}
                case INVALID_ENDPOINT->{if(store.invalidEndpoint(claim))outcome("invalid_endpoint");}
                case RETRYABLE->{if(store.retry(claim,"PROVIDER_RETRY",result.retryAfter()))outcome("retry");}
                case INVALID_PAYLOAD->terminal(claim,"FAILED","INVALID_PAYLOAD");
                case REJECTED->terminal(claim,"FAILED","PROVIDER_REJECTED");
            }
        }catch(PushEndpointCipher.Unavailable invalid){terminal(claim,"FAILED","ENDPOINT_UNAVAILABLE");}
        catch(RuntimeException unavailable){store.retry(claim,"POLICY_OR_DATABASE_UNAVAILABLE",Duration.ZERO);}
    }
    private void terminal(PushDeliveryStore.Claim claim,String status,String code){if(store.terminal(claim,status,code,null))outcome(status.toLowerCase(Locale.ROOT));}
    private void outcome(String result){meters.counter("clientbus.notification.push.attempts","result",result).increment();}
    @PreDestroy public void close(){calls.shutdownNow();}
}
