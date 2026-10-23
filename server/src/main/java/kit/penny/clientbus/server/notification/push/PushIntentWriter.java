package kit.penny.clientbus.server.notification.push;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Map;
import java.util.UUID;

@Service
public class PushIntentWriter {
    private final NamedParameterJdbcTemplate jdbc;
    public PushIntentWriter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Called once, only for a newly inserted notification, in its domain transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createIntents(UUID notificationId) {
        jdbc.update("""
            insert into dbo.notificationdelivery
                (id,notificationid,registrationid,bindingversion,channel,status,attempts,claimversion,nextattemptat,expiresat)
            select gen_random_uuid(),n.id,i.id,i.bindingversion,'MOBILE_PUSH','PENDING',0,0,clock_timestamp(),n.alertexpiresat
            from dbo.notification n join dbo.pushinstallation i
                on i.recipientemployeeid=n.recipientemployeeid and i.organizationid=n.organizationid
            where n.id=:id and n.readat is null and n.alertinvalidatedat is null
              and n.alertexpiresat>clock_timestamp() and i.enabled and i.activeuntil>clock_timestamp()
              and i.reportedpermission in ('GRANTED','PROVISIONAL')
            on conflict (notificationid,registrationid,bindingversion,channel) do nothing
            """,Map.of("id",notificationId));
    }
}
