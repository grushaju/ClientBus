package kit.penny.clientbus.crm.api;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
public record CrmAppointmentMaster(String externalMasterId, String displayName, LocalDateTime localStart,
        LocalDateTime localEnd, Long durationSeconds, BigDecimal price, String currency, Set<String> presentFields) {
    public CrmAppointmentMaster { presentFields=Set.copyOf(presentFields); }
    public CrmAppointmentMaster(String id,String name,LocalDateTime start,LocalDateTime end,Long duration,BigDecimal price,String currency) {
        this(id,name,start,end,duration,price,currency,Set.of("displayName","localStart","localEnd","durationSeconds","price","currency"));
    }
}
