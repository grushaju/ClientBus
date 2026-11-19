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
 */
@Component
public class CorePhoneColumnBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CorePhoneColumnBootstrap.class);

    private static final String TABLE = "dbo.clientphone";

    /** Цифры телефона без форматирования; null остаётся null — мусорный номер не признак. */
    static final String NORMALIZED_EXPRESSION = "case when " + digits() + " ~ '^[0-9]{7,15}$' then " + digits() + " end";

    /** Хвост из последних десяти цифр: ровно то, чем ядро пользуется как подсказкой. */
    static final String TAIL_EXPRESSION = "case when length(" + NORMALIZED_EXPRESSION + ")>=10 then right("
            + NORMALIZED_EXPRESSION + ",10) end";

    static final Column NORMALIZED = new Column("normalizedphone", NORMALIZED_EXPRESSION,
            "create index if not exists clientphone_normalized_ix on dbo.clientphone (normalizedphone, clientid)",
            "clientphone_normalized_ix");
    static final Column TAIL = new Column("phonetail", TAIL_EXPRESSION,
            "create index if not exists clientphone_tail_ix on dbo.clientphone (phonetail, clientid)",
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
        if (Integer.valueOf(0).equals(jdbc.queryForObject(
                "select case when to_regclass(?) is null then 0 else 1 end", Integer.class, TABLE))) {
            log.warn("Нормализованные колонки {} пропущены: таблица ещё не создана", TABLE);
            return;
        }
        for (Column column : List.of(NORMALIZED, TAIL)) {
            ensure(column);
        }
    }

    private void ensure(Column column) {
        List<String> stored = definition(column);
        if (stored.size() == 1 && canonical(column, stored.get(0))) {
            return; // рабочая база: один дешёвый запрос к каталогу на старт
        }
        boolean repair = !stored.isEmpty();
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(status -> {
                    jdbc.execute("lock table " + TABLE + " in share row exclusive mode");
                    if (repair) {
                        // Колонка пересоздаётся целиком: значение в вычисляемой колонке
                        // нельзя «поправить», база пересчитывает его на добавлении.
                        jdbc.execute("alter table " + TABLE + " drop column " + column.name());
                    }
                    jdbc.execute("alter table " + TABLE + " add column " + column.name()
                            + " text generated always as (" + column.expression() + ") stored");
                    jdbc.execute(column.index());
                });
        if (repair) {
            log.warn("Нормализованная колонка {}.{} не соответствовала правилу и пересоздана", TABLE, column.name());
        } else {
            log.info("Нормализованная колонка создана: {}.{}", TABLE, column.name());
        }
    }

    /** Форма колонки глазами базы: признак вычисляемости и текст выражения. */
    private List<String> definition(Column column) {
        return jdbc.queryForList("""
                select case when a.attgenerated = 's' then pg_get_expr(d.adbin, a.attrelid) end as expression
                  from pg_attribute a
                  join pg_class c on c.oid = a.attrelid
                  join pg_namespace n on n.oid = c.relnamespace
                  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where n.nspname = 'dbo' and c.relname = 'clientphone' and a.attname = ? and not a.attisdropped
                """, String.class, column.name());
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
