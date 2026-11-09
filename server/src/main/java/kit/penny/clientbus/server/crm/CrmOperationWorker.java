package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** Database scanning resumes REQUESTED work. An expired IN_FLIGHT is never dispatched again. */
@Component
public class CrmOperationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(CrmOperationWorker.class);
    private final CrmOperationService operations;
    private final Set<UUID> offered=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),
            task -> { var thread=new Thread(task,"crm-command"); thread.setDaemon(true); return thread; },new ThreadPoolExecutor.AbortPolicy());
    public CrmOperationWorker(CrmOperationService operations) { this.operations=operations; }
    @Scheduled(initialDelayString="${clientbus.crm.operations.poll-ms:3000}",fixedDelayString="${clientbus.crm.operations.poll-ms:3000}",scheduler="crmScheduleScheduler")
    public void poll() {
        try {
            operations.queueConfirmedReads();
            for (var id:operations.due()) {
                if (!offered.add(id)) continue;
                try { executor.execute(() -> { try { operations.run(id); } finally { offered.remove(id); } }); }
                catch (RejectedExecutionException full) { offered.remove(id); break; }
            }
        } catch (RuntimeException failure) { LOG.warn("CRM operation scan failed: {}",failure.getClass().getSimpleName()); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
