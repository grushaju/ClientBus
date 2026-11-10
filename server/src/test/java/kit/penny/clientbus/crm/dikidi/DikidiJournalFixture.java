package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic transport only: no sockets or real credentials. Used through the server workers. */
public final class DikidiJournalFixture implements AutoCloseable {
    public final AtomicInteger physical=new AtomicInteger(),dayLoads=new AtomicInteger(),logins=new AtomicInteger();
    public volatile int hour=10;
    public volatile Runnable beforeDayRead=()->{};
    public final DikidiProvider provider;
    public DikidiJournalFixture(CrmJournalPort journal){
        this(journal,true);
    }
    public DikidiJournalFixture(CrmJournalPort journal,boolean reuse){
        provider=new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(1),Duration.ofSeconds(5),65536),()->new DikidiTransport(){
            int auth;
            public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout){
                physical.incrementAndGet();
                if(auth++==0)return new Response(200,"<input type='hidden' name='telegram_csrf' value='csrf'>");
                if(auth==2)return new Response(200,"{\"html\":\"<input type='hidden' name='csrf' value='step2'>\"}");
                if(auth==3){logins.incrementAndGet();return new Response(200,"{\"callback\":[\"sw.auth.complete();\"]}");}
                if("GET".equals(method))return new Response(200,"journal");
                dayLoads.incrementAndGet();beforeDayRead.run();String day=form.stream().filter(e->e.getKey().endsWith("[date_from]")).findFirst().orElseThrow().getValue();
                StringBuilder rows=new StringBuilder();
                for(int client=1;client<=3;client++){
                    if(client>1)rows.append(',');String id=day.replace("-","")+client;
                    rows.append("\"").append(id).append("\":{\"id\":\"").append(id).append("\",\"company_id\":\"100\",\"item_id\":\"m1\",\"item_type\":\"master\",\"date\":\"").append(day)
                            .append("\",\"begin\":\"").append(String.format(java.util.Locale.ROOT,"%02d:00:00",hour)).append("\",\"end\":\"").append(String.format(java.util.Locale.ROOT,"%02d:00:00",hour+1)).append("\",\"duration\":3600,\"info\":{\"client_id\":\"").append(client)
                            .append("\",\"client_is_system\":\"0\",\"client_name\":\"Client\",\"is_time_reservation\":\"0\",\"is_group_session\":\"0\",\"is_deleted\":\"0\",\"status_id\":\"1\"}}");
                }
                return new Response(200,"{\"company\":{\"accesses\":{\"100\":{\"actor\":{\"id\":\"actor\"}}}},\"masters\":{\"info\":{\"ids\":[\"m1\"],\"data\":{\"m1\":{\"id\":\"m1\",\"item_type\":\"master\",\"is_master\":\"1\",\"title\":\"Master\"}}},\"records\":{\"data\":{\"m1\":{\""+day+"\":{"+rows+"}}}}}}");
            }
        }).journalCache(journal,Duration.ofSeconds(30),128,1024*1024,65536,reuse);
    }
    public void close(){provider.close();}
}
