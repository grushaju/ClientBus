package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class CrmJournalStore implements CrmJournalPort {
    private final JdbcTemplate jdbc;
    public CrmJournalStore(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(5);}
    public long epoch(UUID id){return jdbc.query("select journalepoch from crm.project where id=?",(rs,index)->rs.getLong(1),id).stream().findFirst().orElseThrow(()->new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH));}
    public long begin(UUID id,long epoch){return jdbc.query("update crm.project set journalreadsequence=journalreadsequence+1 where id=? and journalepoch=? returning journalreadsequence",(rs,index)->rs.getLong(1),id,epoch).stream().findFirst().orElseThrow(()->new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH));}
    public void invalidate(UUID id){jdbc.update("update crm.project set journalepoch=journalepoch+1 where id=?",id);}
    public CrmSourceHealth health(UUID connection,long credentials){
        return jdbc.query("update crm.connection set healthgeneration=healthgeneration+1 where id=? and credentialversion=? and status<>'DISABLED' returning healthgeneration",
                (rs,index)->new CrmSourceHealth(connection,credentials,rs.getLong(1)),connection,credentials).stream().findFirst().orElseThrow(()->new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH));
    }
    public boolean current(CrmJournalOptions options,UUID connection,long credentials){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from crm.project p join crm.connection c on c.id=p.connectionid where p.id=? and p.connectionid=? and c.credentialversion=? and p.contextversion=? and p.zonerevision=? and p.journalepoch=? and p.enabled and not p.archived and c.status not in ('DISABLED','REAUTH_REQUIRED'))",Boolean.class,options.projectId(),connection,credentials,options.contextVersion(),options.zoneRevision(),options.epoch()));
    }
}
