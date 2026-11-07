package kit.penny.clientbus.server.crm;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs after Hibernate DDL. CRM remains gated until the index and price storage are ready. */
@Component
public class CrmStorage implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private volatile boolean ready;
    public CrmStorage(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments args) { initialize(); }
    public void initialize() {
        ready = false;
        // Upgrade the legacy index atomically: no window in which two active bindings can be inserted.
        new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(status -> {
                    jdbc.execute("lock table crm.project in share row exclusive mode");
                    jdbc.execute("create unique index if not exists uqprojectdikidiworkspace on crm.project(workspaceid) where provider='DIKIDI' and archived=false");
                    if (validateIndex()) {
                        jdbc.execute("drop index crm.uqprojectdikidiworkspace");
                        jdbc.execute("create unique index uqprojectdikidiworkspace on crm.project(workspaceid) where provider='DIKIDI' and archived=false");
                        validateIndex();
                    }
                    jdbc.execute("alter table crm.project drop constraint if exists projectarchiveck");
                    jdbc.execute("alter table crm.project add constraint projectarchiveck check (not archived or not enabled)");
                });
        normalizePrices();
        ready = true;
    }
    /** Returns true only for the exact legacy index, which may be upgraded. */
    private boolean validateIndex() {
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
                || !"workspaceid".equals(row.get("keycolumn"))
                || !("provider='DIKIDI'".equals(predicate) || "provider='DIKIDI'ANDarchived=false".equals(predicate)))
            throw new IllegalStateException("CRM index does not match its contract: " + predicate);
        return "provider='DIKIDI'".equals(predicate);
    }
    private void normalizePrices() {
        var column=jdbc.queryForMap("""
                select data_type,numeric_scale from information_schema.columns
                where table_schema='crm' and table_name='masterservice' and column_name='price'
                """);
        if (!"numeric".equals(column.get("data_type"))) throw new IllegalStateException("CRM price column is not numeric");
        if (column.get("numeric_scale") != null) {
            // Hibernate can consider numeric(19,4) compatible with numeric and leave its padding in place.
            jdbc.execute("alter table crm.masterservice alter column price type numeric using trim_scale(price)");
        }
        // Also handles padded writes made by an older instance during an application upgrade.
        jdbc.update("update crm.masterservice set price=trim_scale(price) where scale(price)>scale(trim_scale(price))");
    }
    public void requireReady() {
        if (!ready) throw new CrmException(503, "CRM_STORAGE_NOT_READY", "Хранилище CRM ещё не готово.");
    }
}
