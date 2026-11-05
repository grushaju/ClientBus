package kit.penny.clientbus.crm.api;
import java.math.BigDecimal;
import java.util.Set;
public record CrmAppointmentService(String externalServiceId, String externalLineId, String displayName,
        Long durationSeconds, BigDecimal price, String currency, Set<String> presentFields) {
    public CrmAppointmentService { presentFields=Set.copyOf(presentFields); }
    public CrmAppointmentService(String id,String line,String name,Long duration,BigDecimal price,String currency) {
        this(id,line,name,duration,price,currency,Set.of("displayName","durationSeconds","price","currency"));
    }
}
