package kit.penny.clientbus.crm.dikidi;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** One shared budget across sessions and redirects in the opt-in live read. */
final class DikidiAcceptancePolicy implements JdkDikidiTransport.RequestPolicy {
    private static final Map<String,Set<String>> READS=Map.of(
            "dikidi.ru",Set.of("GET /ru/","GET /ru/owner/journal/","POST /ru/owner/ajax/journal/api/",
                    "GET /ru/owner/ajax/profile/index/","POST /ru/owner/ajax/clients/filter/",
                    "GET /mobile/ajax/newrecord/company_services","POST /owner/ajax/sales_payments/get_list/",
                    "GET /ru/owner/ajax/sales_payments/form/"),
            "auth.dikidi.ru",Set.of("POST /ajax/check/auth/","POST /ajax/user/auth/"));
    private static final Set<String> SECTIONS=Set.of("company.accesses","masters.info","masters.services","masters.schedules","masters.records");
    private static final Set<String> OPTIONS=Set.of("request[options][company][accesses][]",
            "request[options][masters][records][0][date_from]","request[options][masters][records][0][date_to]",
            "request[options][masters][schedules][0][date_from]","request[options][masters][schedules][0][date_to]");
    private final AtomicInteger calls=new AtomicInteger();
    private final int limit;
    DikidiAcceptancePolicy() { this(160); }
    DikidiAcceptancePolicy(int limit) {
        if (limit<1) throw new IllegalArgumentException("Invalid live request budget");
        this.limit=limit;
    }
    int calls() { return calls.get(); }
    @Override public void beforeRequest(String method,URI uri,List<Map.Entry<String,String>> form) {
        assertTrue("https".equals(uri.getScheme()) && uri.getUserInfo()==null && uri.getPort()==-1
                && (READS.getOrDefault(uri.getHost(),Set.of()).contains(method+" "+uri.getPath())
                    || "dikidi.ru".equals(uri.getHost()) && "GET".equals(method) && uri.getPath().matches("/ru/owner/ajax/sales/form/[1-9][0-9]{0,17}/")),
                "Only allowlisted authentication and reads may run, including redirects.");
        if (uri.getPath().equals("/ru/owner/ajax/journal/api/"))
            assertTrue(form!=null && form.stream().allMatch(field ->
                    field.getKey().equals("request[get][]") && SECTIONS.contains(field.getValue()) || OPTIONS.contains(field.getKey())),
                    "Journal payload must contain read sections and scope options only.");
        if (uri.getPath().equals("/owner/ajax/sales_payments/get_list/"))
            assertTrue(form!=null && form.size()==3 && form.stream().allMatch(field ->
                    (field.getKey().equals("company") || field.getKey().equals("sale_id")) && field.getValue().matches("[1-9][0-9]{0,17}")
                    || field.getKey().equals("isEdit") && field.getValue().equals("false")),"Payment list payload is read only.");
        assertTrue(calls.getAndUpdate(count -> count<limit ? count+1 : count)<limit,"Live HTTP read budget exceeded.");
    }
}
