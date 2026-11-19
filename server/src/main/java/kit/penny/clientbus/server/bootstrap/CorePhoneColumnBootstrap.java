package kit.penny.clientbus.server.bootstrap;

import kit.penny.clientbus.crm.api.CrmPhone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates generated phone columns using PostgreSQL's own canonical definitions.
 * Optional startup maintenance repairs mismatches with bounded lock and statement timeouts.
 * Set clientbus.schema.phone.repair-on-startup=false outside a maintenance window
 * to fail readiness rather than modify the business table.
 */
@Component
public class CorePhoneColumnBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CorePhoneColumnBootstrap.class);

    private static final String SCHEMA = "dbo";
    private static final String TABLE_NAME = "clientphone";
    private static final String TABLE = SCHEMA + "." + TABLE_NAME;

    /**
     * Потолок ожидания блокировки: без него старт встаёт на неопределённое время за любой
     * длинной транзакцией, которая держит таблицу, и в логах при этом ничего нет.
     */
    private final java.time.Duration lockTimeout;
    private final java.time.Duration statementTimeout;
    private final boolean repairOnStartup;
    private java.util.Map<String,String> expected = java.util.Map.of();

    /** Цифры телефона без форматирования; null остаётся null — мусорный номер не признак. */
    static final String NORMALIZED_EXPRESSION = "case when " + digits() + " ~ '^[0-9]{7,15}$' then " + digits() + " end";

    /** Хвост из последних десяти цифр: ровно то, чем ядро пользуется как подсказкой. */
    static final String TAIL_EXPRESSION = "case when length(" + NORMALIZED_EXPRESSION + ")>=10 then right("
            + NORMALIZED_EXPRESSION + ",10) end";

    static final Column NORMALIZED = new Column("normalizedphone", NORMALIZED_EXPRESSION,
            "create index if not exists clientphone_normalized_ix on " + TABLE + " (normalizedphone, clientid)",
            "clientphone_normalized_ix");
    static final Column TAIL = new Column("phonetail", TAIL_EXPRESSION,
            "create index if not exists clientphone_tail_ix on " + TABLE + " (phonetail, clientid)",
            "clientphone_tail_ix");

    /**
     * @param expression выражение вычисляемой колонки глазами Java
     * @param index индекс, который без этой колонки был бы бессмысленным выражением
     * @param indexName имя индекса lookup'а (колонка, клиент)
     */
    record Column(String name, String expression, String index, String indexName) {
    }

    private final JdbcTemplate jdbc;

    public CorePhoneColumnBootstrap(JdbcTemplate jdbc) {
        this(jdbc, true, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CorePhoneColumnBootstrap(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${clientbus.schema.phone.repair-on-startup:true}") boolean repairOnStartup,
            @org.springframework.beans.factory.annotation.Value("${clientbus.schema.phone.lock-timeout:5s}") java.time.Duration lockTimeout,
            @org.springframework.beans.factory.annotation.Value("${clientbus.schema.phone.statement-timeout:2m}") java.time.Duration statementTimeout) {
        if (lockTimeout == null || lockTimeout.toMillis() < 1 || statementTimeout == null || statementTimeout.toMillis() < 1)
            throw new IllegalArgumentException("Phone schema timeouts must be positive");
        this.jdbc = jdbc; this.repairOnStartup = repairOnStartup;
        this.lockTimeout = lockTimeout; this.statementTimeout = statementTimeout;
    }

    /** Форматирование снимается общей константой: два пути нормализации обязаны стареть вместе. */
    private static String digits() {
        return "translate(phone,'" + CrmPhone.FORMATTING_CHARACTERS + "','')";
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!exists(TABLE)) {
            log.warn("Нормализованные колонки {} пропущены: таблица ещё не создана", TABLE);
            return;
        }
        try {
            expected = expectedDefinitions();
            ensureAll(List.of(NORMALIZED, TAIL));
        } catch (RuntimeException failure) {
            // Дальше молчать нельзя: без колонки выдача кандидатов сломается уже в запросах,
            // а очередь на блокировку таблицы к этому моменту разошлась бы по всему флоту.
            // Старт падает сразу и с объяснением, а не ждёт вечно.
            log.error("Телефонная схема {} не готова. Ремонт при запуске: {}; ожидание блокировки: {}; "
                            + "таймаут SQL: {}. Проверьте причину и настройки clientbus.schema.phone.",
                    TABLE, repairOnStartup, lockTimeout, statementTimeout, failure);
            throw failure;
        }
    }

    /** Колонка в правилах — ещё не значит «всё на месте»: индекс проверяется отдельно. */
    private void ensureAll(List<Column> columns) {
        List<Column> stale = new ArrayList<>();
        for (Column column : columns) {
            List<String> stored = definition(column);
            if (stored.size() == 1 && canonical(column, stored.get(0))) {
                ensureIndex(column);
            } else {
                stale.add(column);
            }
        }
        if (!stale.isEmpty()) {
            requireRepair();
            repair(stale);
        }
    }

    /**
     * Починка всех отставших колонок за один заход: блокировка берётся один раз, а колонки
     * добавляются одним {@code alter} — иначе таблица переписывалась бы по разу на каждую.
     */
    private void repair(List<Column> stale) {
        transaction().executeWithoutResult(ignored -> {
            // Блокировка берётся сразу та, которую всё равно потребует alter: отдельная слабая
            // блокировка до него только создавала бы иллюзию порядка.
            configureTimeouts();
            jdbc.execute("select pg_advisory_xact_lock(12664)");
            jdbc.execute("lock table " + TABLE + " in access exclusive mode");
            List<String> dropped = new ArrayList<>();
            List<Column> added = new ArrayList<>();
            for (Column column : stale) {
                // Под блокировкой каталог перечитывается: пока мы ждали своей очереди, колонку
                // мог создать второй экземпляр приложения, а повторный add упал бы «column
                // already exists» и уронил бы старт целиком.
                List<String> now = definition(column);
                if (now.size() == 1 && canonical(column, now.get(0))) {
                    ensureIndexUnderLock(column);
                    continue;
                }
                if (!now.isEmpty()) {
                    // Значение в вычисляемой колонке нельзя «поправить»: база пересчитывает его
                    // только на добавлении, поэтому колонка уходит целиком.
                    dropped.add(column.name());
                }
                added.add(column);
            }
            if (added.isEmpty()) {
                return;
            }
            if (!dropped.isEmpty()) {
                jdbc.execute("alter table " + TABLE + " drop column " + String.join(", drop column ", dropped));
            }
            List<String> additions = new ArrayList<>();
            for (Column column : added) {
                additions.add("add column " + column.name() + " text generated always as ("
                        + column.expression() + ") stored");
            }
            jdbc.execute("alter table " + TABLE + " " + String.join(", ", additions));
            for (Column column : added) {
                ensureIndexUnderLock(column);
            }
            if (dropped.isEmpty()) {
                log.info("Нормализованные колонки созданы: {}.{} одним изменением таблицы",
                        TABLE, added.stream().map(Column::name).toList());
            } else {
                log.warn("Нормализованные колонки {}.{} не соответствовали правилу и пересозданы",
                        TABLE, added.stream().map(Column::name).toList());
            }
        });
    }

    /**
     * Индекс — отдельный факт о базе: колонка могла пережить его (снят вручную, база
     * восстановлена из дампа без индексов). Без индекса сравнение снова становится
     * сканированием, а выглядит при этом вполне рабочим, поэтому проверяется всегда.
     */
    private void ensureIndex(Column column) {
        if (indexMatches(column)) {
            return;
        }
        // Блокировки ещё нет, поэтому короткая отдельная транзакция: set local без
        // транзакции не значил бы ничего.
        requireRepair();
        transaction().executeWithoutResult(ignored -> {
            configureTimeouts();
            jdbc.execute("select pg_advisory_xact_lock(12664)");
            jdbc.execute("lock table " + TABLE + " in share mode");
            ensureIndexUnderLock(column);
        });
    }

    /** Обслуживание сериализовано advisory-блокировкой; используется то же соединение. */
    private void ensureIndexUnderLock(Column column) {
        if (indexMatches(column)) {
            return;
        }
        if (exists(SCHEMA + "." + column.indexName())) {
            var owners = jdbc.queryForList("select indrelid=to_regclass(?) from pg_index where indexrelid=to_regclass(?)",
                    Boolean.class, TABLE, SCHEMA + "." + column.indexName());
            if (owners.size()!=1 || !Boolean.TRUE.equals(owners.getFirst()))
                throw new IllegalStateException("Phone index name belongs to another object: " + column.indexName());
            jdbc.execute("drop index " + SCHEMA + "." + column.indexName());
        }
        createIndex(column);
        logIndexRestored(column);
    }

    private void createIndex(Column column) {
        configureTimeouts();
        jdbc.execute(column.index());
    }

    private boolean indexMatches(Column column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from pg_index i join pg_class c on c.oid=i.indexrelid
                  join pg_am am on am.oid=c.relam
                  where i.indexrelid=to_regclass(?) and i.indrelid=to_regclass(?)
                    and i.indisvalid and i.indisready and not i.indisunique
                    and i.indpred is null and i.indnkeyatts=2 and am.amname='btree'
                    and pg_get_indexdef(i.indexrelid,1,true)=?
                    and pg_get_indexdef(i.indexrelid,2,true)='clientid')
                """, Boolean.class, SCHEMA + "." + column.indexName(), TABLE, column.name()));
    }

    private void logIndexRestored(Column column) {
        log.warn("Индекс {} восстановлен: он оказался снят, и кандидаты привязки снова "
                + "читались бы сканированием таблицы", column.indexName());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
    }

    private boolean exists(String object) {
        return Integer.valueOf(1).equals(jdbc.queryForObject(
                "select case when to_regclass(?) is null then 0 else 1 end", Integer.class, object));
    }

    /** Форма колонки глазами базы: признак вычисляемости и текст выражения. */
    private List<String> definition(Column column) {
        return jdbc.queryForList("""
                select case when a.attgenerated = 's' and a.atttypid='text'::regtype then pg_get_expr(d.adbin, a.attrelid) end as expression
                  from pg_attribute a
                  join pg_class c on c.oid = a.attrelid
                  join pg_namespace n on n.oid = c.relnamespace
                  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where n.nspname = ? and c.relname = ? and a.attname = ? and not a.attisdropped
                """, String.class, SCHEMA, TABLE_NAME, column.name());
    }

    private boolean canonical(Column column, String stored) {
        return stored != null && stored.equals(expected.get(column.name()));
    }
    private void requireRepair() {
        if (!repairOnStartup) throw new IllegalStateException("Phone schema needs maintenance; enable clientbus.schema.phone.repair-on-startup during maintenance");
    }
    private void configureTimeouts() {
        jdbc.execute("set local lock_timeout = '" + lockTimeout.toMillis() + "ms'");
        jdbc.execute("set local statement_timeout = '" + statementTimeout.toMillis() + "ms'");
    }
    // PostgreSQL parses/deparses both sides; no business-data sampling proves equivalence.
    private java.util.Map<String,String> expectedDefinitions() {
        return transaction().execute(status -> {
            configureTimeouts();
            String table = "clientbus_phone_expected_" + java.util.UUID.randomUUID().toString().replace("-", "");
            String phoneType = jdbc.queryForObject("select format_type(atttypid,atttypmod) from pg_attribute where attrelid=to_regclass(?) and attname='phone' and not attisdropped",String.class,TABLE);
            jdbc.execute("create temporary table " + table + " (phone " + phoneType + ", normalizedphone text generated always as ("
                    + NORMALIZED_EXPRESSION + ") stored, phonetail text generated always as (" + TAIL_EXPRESSION + ") stored) on commit drop");
            var result = new java.util.HashMap<String,String>();
            jdbc.query("select a.attname, pg_get_expr(d.adbin,d.adrelid) from pg_attribute a join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum where a.attrelid=to_regclass(?)",
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(row.getString(1), row.getString(2)), table);
            return java.util.Map.copyOf(result);
        });
    }

}
