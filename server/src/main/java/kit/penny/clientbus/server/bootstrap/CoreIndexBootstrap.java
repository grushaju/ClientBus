package kit.penny.clientbus.server.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Частичные уникальные индексы dbo.conversation и dbo.outboundsendcallback, которых нет в
 * Hibernate-каноне (13.2b; закрытие бэклога 19.11.2026).
 *
 * <p>Причина, по которой одного Flyway не хватает: миграция выполняется ДО Hibernate, а бизнес-схему
 * на чистой БД создаёт Hibernate ПОСЛЕ Flyway. Поэтому каждая ветвь
 * {@code V2__conversation_dialog_reference.sql} охранена {@code to_regclass} и на чистой БД является
 * no-op — частичные индексы доставались только существующим базам. Свежие окружения (то есть почти все
 * текущие) жили без этих ограничений: гонка {@code createConversationInternal} не блокировалась ничем,
 * а журнал корреляции раннего результата отправки мог принять два ожидания в один и тот же слот.
 * Hibernate выразить частичный индекс и индекс по выражению не может, поэтому канон доводится здесь —
 * тот же порядок, что у {@code CrmStorage}: после DDL, при каждом старте.
 *
 * <p>Индекс сверяется по определению, а не по имени (как {@code CrmStorage.validateIndex()} у
 * {@code uqprojectdikidiworkspace}): индекс с верным именем, но без {@code where} или без уникальности —
 * это другой запрет, и молча принять его значило бы хранить не то ограничение, которое заявлено. Такой
 * индекс пересоздаётся; окно между drop и create закрывается тем же {@code lock table ... in share row
 * exclusive mode}, что и у соседа, иначе в него успели бы вставить дубликаты.
 *
 * <p>Миграцию это не дублирует вредно: {@code V2} уже применён на существующих базах, поэтому его текст
 * нельзя править даже ради комментария — Flyway сверяет контрольную сумму и падает на следующем старте.
 * Здесь {@code create ... if not exists}, а сверка определения не даёт текстам двух путей разъезжаться.
 *
 * <p>Дубликаты, из-за которых индекс не создаётся, не «чинятся» молча: как и в {@code crm.staffaccess},
 * старт падает с внятным сообщением. Молча оставить базу без ограничения — значит оставить ровно ту гонку,
 * ради устранения которой всё это заведено.
 */
@Component
public class CoreIndexBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CoreIndexBootstrap.class);

    /** Диалог уникален по внешней ссылке в пределах аккаунта канала. */
    static final Spec DIALOG_UNIQUE = new Spec(
            "conversation_channel_client_dialog_uix", "dbo.conversation",
            "create unique index if not exists conversation_channel_client_dialog_uix"
                    + " on dbo.conversation (channelaccountid, dialogexternalid)"
                    + " where dialogexternalid is not null",
            "dialogexternalid is not null", null,
            """
            select 1 from dbo.conversation where dialogexternalid is not null
             group by channelaccountid, dialogexternalid having count(*) > 1 limit 1""",
            "dbo.conversation: у одного канала несколько диалогов с одинаковой внешней ссылкой — "
                    + "снесите дубликат (или проставьте dialogexternalid), иначе ограничение диалога не создать");

    /** Легаси-строки без ссылки: по-прежнему не более одного диалога на участника. */
    static final Spec ONE_DIALOG_PER_PARTICIPANT = new Spec(
            "conversation_channel_client_nodialog_uix", "dbo.conversation",
            "create unique index if not exists conversation_channel_client_nodialog_uix"
                    + " on dbo.conversation (channelaccountid, clientaccountid)"
                    + " where dialogexternalid is null",
            "dialogexternalid is null", null,
            """
            select 1 from dbo.conversation where dialogexternalid is null
             group by channelaccountid, clientaccountid having count(*) > 1 limit 1""",
            "dbo.conversation: у участника без внешней ссылки больше одного диалога — "
                    + "объедините их или проставьте dialogexternalid, иначе легаси-ограничение 1:1 не создать");

    /**
     * Слот корреляции раннего результата: ссылка диалога, а при её отсутствии id участника (coalesce),
     * плюс pending id участника — он может повторяться в разных диалогах.
     */
    static final Spec CALLBACK_SLOT_UNIQUE = new Spec(
            "outboundsendcallback_slot_uix", "dbo.outboundsendcallback",
            "create unique index if not exists outboundsendcallback_slot_uix"
                    + " on dbo.outboundsendcallback (channelaccountid,"
                    + " coalesce(dialogexternalid, recipientexternalid), pendingexternalid)",
            null, "coalesce(dialogexternalid, recipientexternalid)",
            """
            select 1 from dbo.outboundsendcallback
             group by channelaccountid, coalesce(dialogexternalid, recipientexternalid), pendingexternalid
            having count(*) > 1 limit 1""",
            "dbo.outboundsendcallback: в один слот корреляции записано несколько ожиданий — "
                    + "очистите дублирующиеся строки (истёкшие удаляются retention'ом), иначе слот не уникален");

    /**
     * @param predicate ожидаемый предикат частичного индекса (пусто — индексу предикат не положен)
     * @param contains обязательный фрагмент определения для индексов по выражению
     */
    record Spec(String index, String table, String create, String predicate, String contains,
                String duplicates, String remedy) {
    }

    private final JdbcTemplate jdbc;

    public CoreIndexBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Spec spec : List.of(DIALOG_UNIQUE, ONE_DIALOG_PER_PARTICIPANT, CALLBACK_SLOT_UNIQUE)) {
            ensure(spec);
        }
    }

    private void ensure(Spec spec) {
        // Тот же охранительный жест, что у самой миграции: без таблицы ни проверять, ни создавать нечего.
        if (Integer.valueOf(0).equals(jdbc.queryForObject("select case when to_regclass(?) is null then 0 else 1 end",
                Integer.class, spec.table()))) {
            log.warn("Канонический индекс {} пропущен: таблица {} ещё не создана", spec.index(), spec.table());
            return;
        }

        List<Map<String, Object>> present = definition(spec);
        if (present.size() == 1 && matches(spec, present.get(0))) {
            return; // работающая база: одна дешёвая проверка каталога на старт, без сканирования данных
        }

        if (!jdbc.queryForList(spec.duplicates(), String.class).isEmpty()) {
            throw new IllegalStateException(spec.remedy());
        }

        boolean repair = !present.isEmpty();
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(status -> {
                    jdbc.execute("lock table " + spec.table() + " in share row exclusive mode");
                    if (repair) {
                        // DROP INDEX принимает schema.index, а не table.index: трёхсоставное имя
                        // PostgreSQL читает как ссылку на другую базу и отказывает.
                        jdbc.execute("drop index " + schema(spec) + "." + spec.index());
                    }
                    jdbc.execute(spec.create());
                });
        if (repair) {
            log.warn("Канонический индекс {} не соответствовал определению ({}) и пересоздан",
                    spec.index(), present.get(0).get("definition"));
        } else {
            log.info("Канонический индекс создан: {}.{}", spec.table(), spec.index());
        }
    }

    /** Схема таблицы-хозяина: pg_catalog и DDL работают с парой schema.object, не table.object. */
    private static String schema(Spec spec) {
        return spec.table().substring(0, spec.table().indexOf('.'));
    }

    /** Свойства индекса глазами базы: уникальность, предикат, полное определение. */
    private List<Map<String, Object>> definition(Spec spec) {
        return jdbc.queryForList("""
                select i.indisunique,
                       coalesce(pg_get_expr(i.indpred, i.indrelid), '') as predicate,
                       pg_get_indexdef(i.indexrelid) as definition
                  from pg_index i
                  join pg_class c on c.oid = i.indexrelid
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = ? and c.relname = ?
                """, schema(spec), spec.index());
    }

    private static boolean matches(Spec spec, Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("indisunique"))) {
            return false;
        }
        if (!normalize(spec.predicate()).equals(normalize((String) row.get("predicate")))) {
            return false;
        }
        String definition = String.valueOf(row.get("definition")).toLowerCase(Locale.ROOT);
        return spec.contains() == null || definition.contains(spec.contains());
    }

    /** pg_get_expr оборачивает предикат в скобки и расставляет пробелы по-своему — сравниваем суть. */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[()\\s]", "");
    }
}
