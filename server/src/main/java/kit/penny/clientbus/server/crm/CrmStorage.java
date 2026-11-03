package kit.penny.clientbus.server.crm;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runs after Hibernate DDL. CRM endpoints/workers remain gated until the index is verified. */
@Component
public class CrmStorage implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private volatile boolean ready;
    public CrmStorage(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments args) { initialize(); }
    public void initialize() {
        ready = false;
        jdbc.execute("create unique index if not exists uqprojectdikidiworkspace on crm.project(workspaceid) where provider='DIKIDI'");
        var rows = jdbc.queryForList("""
                select i.indisunique, i.indisvalid, i.indnatts, i.indnkeyatts,
                    tn.nspname as tableschema, t.relname as tablename,
                    pg_get_indexdef(i.indexrelid,1,true) as keycolumn,
                    pg_get_expr(i.indpred,i.indrelid) as predicate
                from pg_index i join pg_class idx on idx.oid=i.indexrelid
                join pg_namespace ns on ns.oid=idx.relnamespace
                join pg_class t on t.oid=i.indrelid join pg_namespace tn on tn.oid=t.relnamespace
                where ns.nspname='crm' and idx.relname='uqprojectdikidiworkspace'
                """);
        if (rows.size() != 1) throw new IllegalStateException("CRM index is missing");
        var row = rows.getFirst();
        String predicate = String.valueOf(row.get("predicate")).replaceAll("::[a-zA-Z ]+", "").replaceAll("[()\\s]", "");
        if (!Boolean.TRUE.equals(row.get("indisunique")) || !Boolean.TRUE.equals(row.get("indisvalid"))
                || ((Number)row.get("indnatts")).intValue() != 1 || ((Number)row.get("indnkeyatts")).intValue() != 1
                || !"crm".equals(row.get("tableschema")) || !"project".equals(row.get("tablename"))
                || !"workspaceid".equals(row.get("keycolumn")) || !"provider='DIKIDI'".equals(predicate))
            throw new IllegalStateException("CRM index does not match its contract");
        ready = true;
    }
    public void requireReady() {
        if (!ready) throw new CrmException(503, "CRM_STORAGE_NOT_READY", "Хранилище CRM ещё не готово.");
    }
}

