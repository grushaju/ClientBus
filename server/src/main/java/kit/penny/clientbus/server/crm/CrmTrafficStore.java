package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmTrafficPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Durable connection-wide pause; credential rotation never clears it. */
@Component
public class CrmTrafficStore implements CrmTrafficPort {
    private final JdbcTemplate jdbc;
    public CrmTrafficStore(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(5);}
    public Pause current(UUID id){
        return jdbc.query("select requestnotbefore,requestbackoff from crm.connection where id=?",(rs,index)->new Pause(rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toInstant(),rs.getInt(2)),id).stream().findFirst().orElse(Pause.NONE);
    }
    public Pause block(UUID id,Instant until){
        return jdbc.query("update crm.connection set requestnotbefore=greatest(requestnotbefore,?),requestbackoff=least(30,requestbackoff+1) where id=? returning requestnotbefore,requestbackoff",
                (rs,index)->new Pause(rs.getTimestamp(1).toInstant(),rs.getInt(2)),Timestamp.from(until),id).stream().findFirst().orElseThrow(CrmStore::notFound);
    }
}
