package kit.penny.clientbus.server.crm;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Scheduling policy, distinct from provider protocol limits and projection state. */
@Component
@ConfigurationProperties("clientbus.crm.sync")
public class CrmSyncProperties {
    public enum Dataset { CATALOG, CLIENTS, CLIENT_STATISTICS, APPOINTMENTS, CLIENT_HISTORY, SCHEDULES, HISTORY, FINANCE }
    public static class Policy {
        private boolean enabled=true;
        private Duration refreshInterval=Duration.ofMinutes(15), staleAfter=Duration.ofDays(1), maxRunDuration=Duration.ofMinutes(1), continuationDelay=Duration.ofSeconds(5), itemRetryDelay=Duration.ofMinutes(15);
        private int batchSize=5, maxPagesPerRun=10, pastDays=7, futureDays=30, chunkDays=1, priority=50;
        public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
        public Duration getRefreshInterval(){return refreshInterval;} public void setRefreshInterval(Duration v){refreshInterval=v;}
        public Duration getStaleAfter(){return staleAfter;} public void setStaleAfter(Duration v){staleAfter=v;}
        public Duration getMaxRunDuration(){return maxRunDuration;} public void setMaxRunDuration(Duration v){maxRunDuration=v;}
        public Duration getContinuationDelay(){return continuationDelay;} public void setContinuationDelay(Duration v){continuationDelay=v;}
        public Duration getItemRetryDelay(){return itemRetryDelay;} public void setItemRetryDelay(Duration v){itemRetryDelay=v;}
        public int getBatchSize(){return batchSize;} public void setBatchSize(int v){batchSize=v;}
        public int getMaxPagesPerRun(){return maxPagesPerRun;} public void setMaxPagesPerRun(int v){maxPagesPerRun=v;}
        public int getPastDays(){return pastDays;} public void setPastDays(int v){pastDays=v;}
        public int getFutureDays(){return futureDays;} public void setFutureDays(int v){futureDays=v;}
        public int getChunkDays(){return chunkDays;} public void setChunkDays(int v){chunkDays=v;}
        public int getPriority(){return priority;} public void setPriority(int v){priority=v;}
    }
    private boolean enabled=true;
    private int workers=4;
    private int connectionWorkers=1;
    private int manualPriority=100;
    private Duration schedulerTick=Duration.ofSeconds(10), planningInterval=Duration.ofMinutes(1), leaseDuration=Duration.ofMinutes(2), heartbeatInterval=Duration.ofSeconds(20);
    private Duration retryInitialDelay=Duration.ofSeconds(30), retryMaxDelay=Duration.ofMinutes(30), agingInterval=Duration.ofMinutes(1);
    private Duration retiredJobRetention=Duration.ofDays(30);
    private double retryMultiplier=2, retryJitter=0.2;
    private Map<Dataset,Policy> datasets=new EnumMap<>(Dataset.class);
    public CrmSyncProperties(){for(var dataset:Dataset.values())datasets.put(dataset,new Policy());datasets.get(Dataset.FINANCE).setEnabled(false);}
    public Policy policy(Dataset dataset){return datasets.get(dataset);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getWorkers(){return workers;} public void setWorkers(int v){workers=v;}
    public int getConnectionWorkers(){return connectionWorkers;} public void setConnectionWorkers(int v){connectionWorkers=v;}
    public int getManualPriority(){return manualPriority;} public void setManualPriority(int v){manualPriority=v;}
    public Duration getSchedulerTick(){return schedulerTick;} public void setSchedulerTick(Duration v){schedulerTick=v;}
    public Duration getPlanningInterval(){return planningInterval;} public void setPlanningInterval(Duration v){planningInterval=v;}
    public Duration getLeaseDuration(){return leaseDuration;} public void setLeaseDuration(Duration v){leaseDuration=v;}
    public Duration getHeartbeatInterval(){return heartbeatInterval;} public void setHeartbeatInterval(Duration v){heartbeatInterval=v;}
    public Duration getRetryInitialDelay(){return retryInitialDelay;} public void setRetryInitialDelay(Duration v){retryInitialDelay=v;}
    public Duration getRetryMaxDelay(){return retryMaxDelay;} public void setRetryMaxDelay(Duration v){retryMaxDelay=v;}
    public Duration getAgingInterval(){return agingInterval;} public void setAgingInterval(Duration v){agingInterval=v;}
    public Duration getRetiredJobRetention(){return retiredJobRetention;} public void setRetiredJobRetention(Duration v){retiredJobRetention=v;}
    public double getRetryMultiplier(){return retryMultiplier;} public void setRetryMultiplier(double v){retryMultiplier=v;}
    public double getRetryJitter(){return retryJitter;} public void setRetryJitter(double v){retryJitter=v;}
    public Map<Dataset,Policy> getDatasets(){return datasets;} public void setDatasets(Map<Dataset,Policy> v){datasets.putAll(v);}
    @PostConstruct public void validate(){
        positive(schedulerTick);positive(planningInterval);positive(leaseDuration);positive(heartbeatInterval);positive(retryInitialDelay);positive(retryMaxDelay);positive(agingInterval);
        if(retiredJobRetention==null||retiredJobRetention.compareTo(Duration.ofDays(1))<0||retiredJobRetention.compareTo(Duration.ofDays(3650))>0)throw new IllegalArgumentException("Invalid retired CRM job retention");
        if(workers<1||workers>64||connectionWorkers<1||connectionWorkers>workers||manualPriority<0||manualPriority>1000||leaseDuration.compareTo(Duration.ofSeconds(90))<0||heartbeatInterval.multipliedBy(3).compareTo(leaseDuration)>=0||retryMaxDelay.compareTo(retryInitialDelay)<0
                ||!Double.isFinite(retryMultiplier)||retryMultiplier<1||retryMultiplier>10||!Double.isFinite(retryJitter)||retryJitter<0||retryJitter>1)throw new IllegalArgumentException("Invalid CRM sync execution policy");
        for(var p:datasets.values()){
            positive(p.refreshInterval);positive(p.staleAfter);positive(p.maxRunDuration);positive(p.continuationDelay);positive(p.itemRetryDelay);
            if(p.batchSize<1||p.batchSize>1000||p.maxPagesPerRun<1||p.maxPagesPerRun>10000||p.pastDays<0||p.pastDays>3650||p.futureDays<0||p.futureDays>3650||p.chunkDays<1||p.chunkDays>31||p.priority<0||p.priority>1000)
                throw new IllegalArgumentException("Invalid CRM dataset policy");
        }
        if(policy(Dataset.CLIENT_STATISTICS).batchSize>5)throw new IllegalArgumentException("DIKIDI supports at most five client statistics per batch");
    }
    private static void positive(Duration value){if(value==null||value.compareTo(Duration.ofSeconds(1))<0||value.compareTo(Duration.ofDays(30))>0)throw new IllegalArgumentException("CRM duration must be between 1s and 30d");}
}
