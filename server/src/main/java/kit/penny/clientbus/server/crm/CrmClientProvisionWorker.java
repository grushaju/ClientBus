package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
public class CrmClientProvisionWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CrmClientProvisionWorker.class);
    private final CrmClientProvisioning service;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=Executors.newFixedThreadPool(1,Thread.ofPlatform().daemon().name("crm-client-create").factory());
    public CrmClientProvisionWorker(CrmClientProvisioning service){this.service=service;}
    @Scheduled(initialDelayString="${clientbus.crm.operations.poll-ms:3000}",fixedDelayString="${clientbus.crm.operations.poll-ms:3000}",scheduler="crmScheduleScheduler")
    public void poll(){
        try {
            if(!active.isEmpty())return;
            var ids=service.due();if(ids.isEmpty())return;var id=ids.getFirst();
            if(active.add(id)) {
                try { executor.execute(()->{try{service.run(id);}catch(RuntimeException error){LOG.warn("CRM client operation {} failed: {}",id,error.getClass().getSimpleName());}finally{active.remove(id);}}); }
                catch(RejectedExecutionException stopped){active.remove(id);}
            }
        }catch(RuntimeException error){LOG.warn("CRM client scan failed: {}",error.getClass().getSimpleName());}
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
