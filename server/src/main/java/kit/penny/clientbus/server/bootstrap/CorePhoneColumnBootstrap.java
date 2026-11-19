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
import java.util.Locale;
import java.util.Objects;

/**
 * Нормализованный телефон у учётных записей клиента (закрытие бэклога 19.11.2026).
 *
 * <p>Поиск кандидатов привязки на обеих сторонах (CRM и ядро) сравнивал телефон, снимая
 * форматирование на ходу: {@code translate(p.phone, :formatting, '')} — выражение с
 * параметром. PostgreSQL не сопоставляет индексу выражение с {@code $1}, поэтому оба
 * условия оставались последовательным сканированием по {@code dbo.clientphone} на каждой
 * выдаче кандидатов, а кандидатов оператор запрашивает на каждое действие в форме.
 *
 * <p>Колонка сделана вычисляемой ({@code generated always ... stored}), а не обычной с
 * заполнением разовым бэкфиллом, и вот почему: телефоны пишет в таблицу Hibernate через
 * коллекцию {@code @ElementCollection}, где одному элементу соответствует ровно один
 * столбец. Обычную колонку некому было бы заполнять при вставке — она молча оставалась бы
 * пустой ровно на тех строках, которые добавились после бэкфилла, и поиск начал бы
 * терять клиентов. Вычисляемая колонка считается базой на каждой записи, расхождение
 * между телефоном и его нормализацией становится невозможным по построению, а не по
 * обещанию.
 *
 * <p>Правило нормализации — ровно то, которым оно уже было в SQL: снять только
 * форматирование из общего набора {@link CrmPhone#FORMATTING_CHARACTERS} и признать
 * телефоном только то, что после этого оказалось семью—пятнадцатью цифрами. Набор
 * снимается тем же константным значением из Java, поэтому при его изменении проверка
 * определения не совпадёт и колонка будет пересоздана — вместе со всеми значениями.
 *
 * <p>Индексы обычные, не частичные: условие вида «колонка = параметр» не доказуемо
 * влечёт «колонка not null» для планировщика, и частичный индекс рискнул бы остаться
 * неиспользованным — то есть вернул бы ровно то сканирование, от которого всё это.
 *
 * <p>Честная цена: первое добавление колонки PostgreSQL делает как переписывание таблицы —
 * на это время таблица блокируется целиком, и на большой базе первый после выпуска старт
 * заметно дольше обычного. Дальше проверка обращается только к каталогу и ничего не читает
 * в данных, то есть платить приходится один раз, а не при каждом запуске.
 *
 * <p>Три вещи, которые здесь легко было оставить на честном слове и которые поэтому
 * проверяются явно. Индекс — отдельный факт: колонка могла пережить свой индекс (снят
 * вручную, база поднята из дампа без индексов), и выглядит это как полностью рабочая
 * установка, только кандидаты снова читаются сканированием. Блокировка берётся сразу та,
 * которую всё равно потребует {@code alter} (ACCESS EXCLUSIVE), и каталог под ней
 * перечитывается: при перекате двух экземпляров второй иначе ронял бы старт ошибкой
 * «column already exists». И пересоздание колонки — действие разрушительное, поэтому
 * перед ним значения сверяются со всеми строками: если база разошлась с приложением только в
 * записи выражения (свой депарсер, другая версия), таблица ради косметики не переписывается.
 * Ожидание блокировки ограничено {@link #LOCK_TIMEOUT}: без потолка старт встал бы навсегда
 * за любой длинной транзакцией и не сказал бы в лог ни слова.
 *
 * <p>Колонок две, и обе добавляются одним {@code alter table}. Это не косметика: добавление
 * вычисляемой колонки PostgreSQL делает переписыванием таблицы, и два отдельных {@code alter}
 * платили бы этим дважды за один запуск. Сколько именно экономит объединение на большой базе,
 * здесь не измерялось — экономия следует из формулировки документации, а проверки закрепляют,
 * что объединённый путь создаёт ровно те же колонки и индексы.
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
    private static final String LOCK_TIMEOUT = "60s";

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
        this.jdbc = jdbc;
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
            ensureAll(List.of(NORMALIZED, TAIL));
        } catch (RuntimeException failure) {
            // Дальше молчать нельзя: без колонки выдача кандидатов сломается уже в запросах,
            // а очередь на блокировку таблицы к этому моменту разошлась бы по всему флоту.
            // Старт падает сразу и с объяснением, а не ждёт вечно.
            log.error("Нормализованные колонки {} не готовы: таблица не отдала блокировку за {} "
                            + "либо база недоступна. Удалите длинную транзакцию и повторите запуск.",
                    TABLE, LOCK_TIMEOUT, failure);
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
            jdbc.execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");
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
                boolean generated = now.size() == 1 && now.get(0) != null;
                if (generated && valuesAgree(column)) {
                    // База разошлась с приложением в записи выражения, а не в его смысле.
                    // Пересоздание переписывало бы таблицу ради косметики — и платило бы этим
                    // каждый запуск на любой версии, где депарсер пишет иначе.
                    log.warn("Определение {}.{} записано иначе, чем ожидает приложение, а значения "
                            + "совпадают: колонка не пересоздаётся", TABLE, column.name());
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
                jdbc.execute(column.index());
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
        if (exists(SCHEMA + "." + column.indexName())) {
            return;
        }
        // Блокировки ещё нет, поэтому короткая отдельная транзакция: set local без
        // транзакции не значил бы ничего.
        transaction().executeWithoutResult(ignored -> createIndex(column));
        logIndexRestored(column);
    }

    /** Вызывается, когда ACCESS EXCLUSIVE уже держится: индекс дописывается тем же соединением. */
    private void ensureIndexUnderLock(Column column) {
        if (exists(SCHEMA + "." + column.indexName())) {
            return;
        }
        createIndex(column);
        logIndexRestored(column);
    }

    private void createIndex(Column column) {
        jdbc.execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");
        jdbc.execute(column.index());
    }

    private void logIndexRestored(Column column) {
        log.warn("Индекс {} восстановлен: он оказался снят, и кандидаты привязки снова "
                + "читались бы сканированием таблицы", column.indexName());
    }

    /** Значения колонки сверяются со всех строк, а не предполагаются по тексту определения. */
    private boolean valuesAgree(Column column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select not exists (select 1 from " + TABLE
                + " p where p." + column.name() + " is distinct from (" + column.expression() + "))", Boolean.class));
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
                select case when a.attgenerated = 's' then pg_get_expr(d.adbin, a.attrelid) end as expression
                  from pg_attribute a
                  join pg_class c on c.oid = a.attrelid
                  join pg_namespace n on n.oid = c.relnamespace
                  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where n.nspname = ? and c.relname = ? and a.attname = ? and not a.attisdropped
                """, String.class, SCHEMA, TABLE_NAME, column.name());
    }

    /**
     * Сверяется всё выражение, а не его фрагмент: нормализованный телефон — признака
     * «почти верно» не имеет. Сравнивать текст напрямую нельзя: база отдаёт своё разложение
     * с явными приведениями {@code ::text}, лишними скобками и явным {@code else null},
     * которых в исходном выражении нет. Поэтому сравнивается суть — без кавычек, пробелов,
     * скобок, приведений и добавленной базой ветки else. Косметика убирается с обеих сторон
     * одинаково, а совпадение значений по этому правилу проверяет отдельный интеграционный
     * тест на самых противных написаниях телефона.
     */
    private static boolean canonical(Column column, String stored) {
        return stored != null && shape(stored).equals(shape(column.expression()));
    }

    /** Форма выражения для сравнения: только смысл, без оформления, которое выбирает база. */
    static String shape(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("::text", "")
                // Кавычки базы бывают трёх видов: строковый литерал, идентификатор функции
                // (right депарсится как "right") и обратные кавычки из чужих дампов.
                .replace("'", "")
                .replace("`", "")
                .replace("\"", "")
                .replaceAll("\\s", "")
                .replace("elsenull", "")
                .replace("(", "")
                .replace(")", "");
    }
}
