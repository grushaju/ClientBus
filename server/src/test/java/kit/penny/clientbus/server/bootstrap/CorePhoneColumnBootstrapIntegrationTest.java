package kit.penny.clientbus.server.bootstrap;

import kit.penny.clientbus.crm.api.CrmPhone;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Нормализованный телефон учётных записей клиента на реальной базе (закрытие бэклога
 * 19.11.2026).
 *
 * <p>Проверяется ровно то, на чём держится обещание: колонки считает база, а не код
 * приложения, поэтому их значения обязаны совпадать с Java-правилом {@link CrmPhone} на
 * самых противных написаниях — неразрывные пробелы, буквы внутри номера, слишком короткий
 * и слишком длинный номер. Если эти две нормы разойдутся, оператор увидит строку в списке
 * кандидатов без пометки «совпал по телефону» (или наоборот), и никакие тесты запросов
 * этого не покажут: запрос сверяется с колонкой, а не с реальностью.
 *
 * <p>{@code @Transactional} здесь нет намеренно: строки должны реально попасть в таблицу,
 * чтобы база их пересчитала.
 */
@SpringBootTest
class CorePhoneColumnBootstrapIntegrationTest extends AbstractIntegrationTest {

    /** Маркер пробных клиентов: по нему вычищаются и клиенты, и их телефоны. */
    private static final String PROBE = "phone-cols-";

    /** Пометка колонки: теряется при любом её пересоздании. */
    private static final String MARK = "probnaya-marka";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CorePhoneColumnBootstrap bootstrap;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ClientRepository clients;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private OrganizationEntity organization;

    @Test
    void anEmptyTableDoesNotProveAnIncorrectGeneratedExpressionIsCorrect() {
        jdbc.execute("alter table dbo.clientphone drop column normalizedphone");
        jdbc.execute("alter table dbo.clientphone add column normalizedphone text generated always as (null::text) stored");
        try {
            bootstrap.run(null);
            organization = organizations.saveAndFlush(TestDataFactory.organization());
            var client = new ClientEntity(PROBE + "future", "Probe", organization);
            client.setPhoneList(List.of("+7 (999) 765-43-21"));
            clients.saveAndFlush(client);
            assertEquals("79997654321", jdbc.queryForObject("select normalizedphone from dbo.clientphone where clientid=?",String.class,client.getId()));
        } finally { restoreNormalizedColumn(); }
    }

    @Test
    void validationModeDoesNotRepairMissingIndex() {
        jdbc.execute("drop index dbo.clientphone_tail_ix");
        try {
            var validation = new CorePhoneColumnBootstrap(jdbc,false,java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(5));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->validation.run(null));
            assertTrue(!indexPresent("clientphone_tail_ix"));
        } finally { bootstrap.run(null); }
    }

    @Test
    void clientDeletionLockConflictsWithForeignKeyKeyShare() {
        organization = organizations.saveAndFlush(TestDataFactory.organization());
        var client = clients.saveAndFlush(new ClientEntity(PROBE + "lock", "Probe", organization));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(clients.findByIdForUpdate(client.getId()).isPresent());
            try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("set lock_timeout='200ms'");
                var failure = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> statement.executeQuery("select clientid from dbo.client where clientid='" + client.getId() + "' for key share"));
                assertEquals("55P03", failure.getSQLState());
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @Test
    void wrongIndexDefinitionIsRepairedWithoutRebuildingColumns() {
        markColumn();
        jdbc.execute("drop index dbo.clientphone_tail_ix");
        jdbc.execute("create index clientphone_tail_ix on dbo.clientphone(clientid) where phonetail is not null");
        bootstrap.run(null);
        assertEquals("phonetail", jdbc.queryForObject("select pg_get_indexdef('dbo.clientphone_tail_ix'::regclass,1,true)",String.class));
        assertNull(jdbc.queryForObject("select pg_get_expr(indpred,indrelid) from pg_index where indexrelid='dbo.clientphone_tail_ix'::regclass",String.class));
        bootstrap.run(null);
        assertEquals(MARK, columnMark());
    }

    @AfterEach
    void cleanProbes() {
        jdbc.update("delete from dbo.clientphone where clientid in"
                + " (select clientid from dbo.client where firstname like ?)", PROBE + "%");
        jdbc.update("delete from dbo.client where firstname like ?", PROBE + "%");
    }

    /** Колонки существуют и они вычисляемые; индексы lookup'а при них. */
    @Test
    void normalizedColumnsAreComputedByTheDatabaseOnAFreshStart() {
        for (var column : List.of(CorePhoneColumnBootstrap.NORMALIZED, CorePhoneColumnBootstrap.TAIL)) {
            var stored = jdbc.queryForList("""
                    select case when a.attgenerated='s' then pg_get_expr(d.adbin, a.attrelid) end as expression
                      from pg_attribute a
                      join pg_class c on c.oid=a.attrelid
                      join pg_namespace n on n.oid=c.relnamespace
                      left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                     where n.nspname='dbo' and c.relname='clientphone' and a.attname=? and not a.attisdropped
                    """, String.class, column.name());
            assertEquals(1, stored.size(), "колонка " + column.name() + " отсутствует: " + stored);
            assertTrue(stored.get(0) != null && shape(stored.get(0)).equals(shape(column.expression())),
                    "колонка " + column.name() + " не является вычисляемой этой же формулой: " + stored.get(0));
        }
        for (var column : List.of(CorePhoneColumnBootstrap.NORMALIZED, CorePhoneColumnBootstrap.TAIL)) {
            assertTrue(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='dbo' and indexname=?",
                            Integer.class, column.indexName()) == 1,
                    "индекс " + column.indexName() + " не создан — сравнение по колонке останется сканированием");
        }
    }

    /**
     * База считает ровно то же, что считает Java: то же форматирование, те же семь—пятнадцать
     * цифр, тот же хвост из десяти. Всё, что не номер, остаётся пустотой — иначе мусорный
     * ряд попал бы в выдачу кандидатов с пометкой о совпадении.
     */
    @Test
    void databaseNormalizationEqualsTheJavaRuleOnAwkwardSpellings() {
        var phones = List.of(
                "79991234567",                      // 11 цифр
                "+7 (999) 765-43 21",               // 11 цифр с форматированием
                "+7 (999) 555-44-33",     // то же неразрывными пробелами
                "799912345678",                     // 12 цифр: за пределами короткого номера
                "799912345678901",                  // ровно 15 — потолок ещё номер
                "8999123456789012",                 // 16 цифр — уже нет
                "+7(999)765-43-21x4",               // буква внутри — не номер
                "12345",                            // короче семи — не номер
                "78125550011");                     // 10 цифр: хвост совпадает с самим собой
        organization = organizations.saveAndFlush(TestDataFactory.organization());
        for (var phone : phones) {
            var client = new ClientEntity(PROBE + Math.abs(phone.hashCode()), "Probe", organization);
            client.setPhoneList(List.of(phone));
            clients.saveAndFlush(client);
        }

        var rows = jdbc.queryForList("""
                select p.phone as phone, p.normalizedphone as normalized, p.phonetail as tail
                  from dbo.clientphone p
                  join dbo.client c on c.clientid = p.clientid
                 where c.firstname like ?
                 order by p.phone
                """, PROBE + "%");
        assertEquals(phones.size(), rows.size(), "телефоны не попали в таблицу");

        for (var row : rows) {
            String phone = (String) row.get("phone");
            String expected = CrmPhone.normalize(phone);
            assertEquals(expected, row.get("normalized"), "нормализация разошлась с Java-правилом: " + phone);
            assertEquals(expected == null || expected.length() < 10 ? null : expected.substring(expected.length() - 10),
                    row.get("tail"), "хвост разошёлся с Java-правилом: " + phone);
        }
    }

    /**
     * Пустая нормализация — тоже результат: номер с буквой внутри не должен получить ни
     * нормализацию, ни хвост, иначе он начнёт совпадать с чем попало.
     */
    @Test
    void notAPhoneStaysEmptyInBothColumns() {
        organization = organizations.saveAndFlush(TestDataFactory.organization());
        var client = new ClientEntity(PROBE + "junk", "Probe", organization);
        client.setPhoneList(List.of("+7(999)765-43-21x4"));
        clients.saveAndFlush(client);

        var row = jdbc.queryForMap("select normalizedphone, phonetail from dbo.clientphone where clientid=?",
                client.getId());
        assertNull(row.get("normalizedphone"));
        assertNull(row.get("phonetail"));
    }

    /**
     * Колонка, заведённая кем-то вручную или оставшаяся от прежнего правила, — это не «почти
     * верно»: значения в ней считаются на записи, и молча оставить её значит терять новых
     * клиентов. Поэтому форма сверяется, неверная форма пересоздаётся, а вместе с ней
     * пересчитываются значения.
     */
    @Test
    void aColumnOfTheWrongShapeIsRecomputedOnTheNextRun() {
        jdbc.execute("alter table dbo.clientphone drop column if exists normalizedphone");
        // Обычная колонка с «похожим», но не тем содержимым: имя на месте, правила нет.
        jdbc.execute("alter table dbo.clientphone add column normalizedphone text");

        bootstrap.run(null);

        var stored = jdbc.queryForObject("""
                select case when a.attgenerated='s' then pg_get_expr(d.adbin, a.attrelid) end
                  from pg_attribute a
                  join pg_class c on c.oid=a.attrelid
                  join pg_namespace n on n.oid=c.relnamespace
                  left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                 where n.nspname='dbo' and c.relname='clientphone' and a.attname='normalizedphone'
                """, String.class);
        assertEquals(shape(CorePhoneColumnBootstrap.NORMALIZED_EXPRESSION), shape(stored),
                "колонка не вернулась к правилу: " + stored);

        organization = organizations.saveAndFlush(TestDataFactory.organization());
        var client = new ClientEntity(PROBE + "repair", "Probe", organization);
        client.setPhoneList(List.of("+7 (999) 765-43 21"));
        clients.saveAndFlush(client);
        assertEquals("79997654321", jdbc.queryForObject(
                "select normalizedphone from dbo.clientphone where clientid=?", String.class, client.getId()));
    }

    /**
     * Индекс обязан быть применим к реальному условию запроса: сравнение с параметром по
     * выражению translate() планировщик не использует никак, и подмена колонки выражением
     * вернула бы прежнее сканирование. Сканирование отключается явно, чтобы проверка была
     * повторимой на маленькой таблице: важен сам факт, что план умеет в индекс.
     */
    @Test
    void candidateLookupIsPlannedThroughTheIndex() {
        var plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("""
                    explain (costs off)
                    select c.clientid from dbo.client c
                     where exists (select 1 from dbo.clientphone p
                                    where p.clientid = c.clientid and p.phonetail = '9991234567')
                    """, String.class));
        }).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Имени индекса в плане мало: при выключенном последовательном сканировании база
        // называет любой индекс, даже если проходит по нему целиком и отфильтровывает строки
        // постфактум. Значим ровно поиск по ключу — Index Cond с самой колонкой.
        assertTrue(plan.contains("INDEX COND") && plan.contains("PHONETAIL = '9991234567'"),
                "условие по хвосту телефона читается фильтром, а не поиском по индексу: " + plan);
    }

    /**
     * Индекс — отдельный факт о базе: его могут снять, пока колонка стоит. Оставить это без
     * внимания значит получить полностью рабочую на вид установку, которая читает кандидатов
     * сканированием. Проверка стоит того ещё и потому, что восстановление индекса не должно
     * тащить за собой переписывание таблицы.
     */
    @Test
    void anIndexTakenOffBehindOurBackIsPutBackWithoutTouchingTheColumn() {
        markColumn();
        jdbc.execute("drop index dbo." + CorePhoneColumnBootstrap.TAIL.indexName());
        assertTrue(!indexPresent(CorePhoneColumnBootstrap.TAIL.indexName()), "проба не состоялась: индекс на месте");

        bootstrap.run(null);

        assertTrue(indexPresent(CorePhoneColumnBootstrap.TAIL.indexName()),
                "индекс не восстановлен: сравнение по хвосту снова станет сканированием");
        assertEquals(MARK, columnMark(), "восстановление индекса потянуло за собой пересоздание колонки");
    }

    /**
     * Разная запись одного и того же выражения — не повод переписывать таблицу. Иначе на любой
     * базе, где депарсер пишет иначе, старт платил бы переписыванием каждый раз, а это ровно
     * тот риск, который выглядит как «всё работает, просто медленнее».
     */
    @Test
    void anUnrecognizedExpressionIsReplacedEvenIfExistingValuesAgree() {
        swapNormalizedExpression("^[0123456789]{7,15}$"); // тот же смысл, записанный иначе
        try {
            assertTrue(countProbeRows() > 0, "проба не состоялась: сверять значения не с чем");
            assertTrue(!shape(storedExpression())
                    .equals(shape(CorePhoneColumnBootstrap.NORMALIZED_EXPRESSION)),
                    "проба не состоялась: запись совпала с ожидаемой");
            markColumn();

            bootstrap.run(null);

            assertNull(columnMark());
        } finally {
            restoreNormalizedColumn();
        }
    }

    /**
     * Вторая половина той же правды: если значения действительно расходятся, колонка
     * пересоздаётся. Без этой половины проверка выше прошла бы и на коде, который не
     * пересоздаёт ничего.
     */
    @Test
    void aColumnComputingDifferentValuesIsRecomputedEvenThoughItLooksGenerated() {
        swapNormalizedExpression("^[0-9]{7,11}$"); // потолок ниже правила: двенадцать цифр теряются
        try {
            markColumn();

            bootstrap.run(null);

            assertNull(columnMark(), "колонка с другим правилом осталась на месте: "
                    + "новые клиенты выпадали бы из кандидатов молча");
            assertEquals(shape(CorePhoneColumnBootstrap.NORMALIZED_EXPRESSION),
                    shape(storedExpression()), "колонка не вернулась к правилу");
        } finally {
            restoreNormalizedColumn();
        }
    }

    /**
     * Смешанное состояние — одна колонка верна, соседняя вычисляет не то. Именно здесь
     * объединённое изменение таблицы может ошибиться в обе стороны: задеть то, что уже
     * правильно, или не починить то, что требует пересчёта.
     */
    @Test
    void aColumnThatIsRightIsLeftAloneWhileItsBrokenNeighbourIsRecomputed() {
        markColumn();
        swapTailExpression(9); // хвост из девяти цифр вместо десяти
        try {
            assertTrue(countProbeRows() > 0, "проба не состоялась: сверять значения не с чем");

            bootstrap.run(null);

            assertEquals(MARK, columnMark(), "верная колонка пострадала ради соседней");
            assertEquals(shape(CorePhoneColumnBootstrap.TAIL_EXPRESSION),
                    shape(storedExpression("phonetail")), "испорченная колонка не вернулась к правилу");
            assertTrue(indexPresent(CorePhoneColumnBootstrap.TAIL.indexName()),
                    "индекс починенной колонки не восстановился вместе с ней");
        } finally {
            restoreTailColumn();
        }
    }

    /** Строки с противными написаниями — чтобы сверка значений была проверкой, а не пустотой. */
    private void seedProbePhones() {
        organization = organizations.saveAndFlush(TestDataFactory.organization());
        for (var phone : List.of("+7 (999) 765-43 21", "799912345678", "78125550011", "+7(999)765-43-21x4")) {
            var client = new ClientEntity(PROBE + Math.abs(phone.hashCode()), "Probe", organization);
            client.setPhoneList(List.of(phone));
            clients.saveAndFlush(client);
        }
    }

    private void swapNormalizedExpression(String regex) {
        if (countProbeRows() == 0) seedProbePhones();
        jdbc.execute("alter table dbo.clientphone drop column if exists normalizedphone");
        String digits = "translate(phone,'" + CrmPhone.FORMATTING_CHARACTERS + "','')";
        jdbc.execute("alter table dbo.clientphone add column normalizedphone text generated always as (case when "
                + digits + " ~ '" + regex + "' then " + digits + " end) stored");
    }

    /**
     * Пометка на колонке — единственный неоспоримый признак того, что её не трогали: при
     * пересоздании она теряется всегда, тогда как идентификаторы база умеет выдать заново те
     * же самые, и проверка на них время от времени проходила бы молча.
     */
    private void markColumn() {
        jdbc.execute("comment on column dbo.clientphone.normalizedphone is '" + MARK + "'");
    }

    private String columnMark() {
        return jdbc.queryForObject("""
                select col_description(c.oid, a.attnum)
                  from pg_class c
                  join pg_namespace n on n.oid=c.relnamespace
                  join pg_attribute a on a.attrelid=c.oid
                 where n.nspname='dbo' and c.relname='clientphone' and a.attname='normalizedphone'
                   and not a.attisdropped
                """, String.class);
    }

    private void restoreNormalizedColumn() {
        jdbc.execute("alter table dbo.clientphone drop column if exists normalizedphone");
        bootstrap.run(null);
    }

    /** Хвост по другому правилу: то же основание, другая длина — значения расходятся. */
    private void swapTailExpression(int digits) {
        if (countProbeRows() == 0) seedProbePhones();
        jdbc.execute("alter table dbo.clientphone drop column if exists phonetail");
        String base = CorePhoneColumnBootstrap.NORMALIZED_EXPRESSION;
        jdbc.execute("alter table dbo.clientphone add column phonetail text generated always as (case when length("
                + base + ")>=" + (digits + 1) + " then right(" + base + "," + digits + ") end) stored");
    }

    private void restoreTailColumn() {
        jdbc.execute("alter table dbo.clientphone drop column if exists phonetail");
        bootstrap.run(null);
    }

    private int countProbeRows() {
        return jdbc.queryForObject("select count(*) from dbo.clientphone p join dbo.client c on c.clientid=p.clientid"
                + " where c.firstname like ?", Integer.class, PROBE + "%");
    }

    private String storedExpression() {
        return storedExpression("normalizedphone");
    }

    private String storedExpression(String column) {
        return jdbc.queryForObject("""
                select case when a.attgenerated='s' then pg_get_expr(d.adbin, a.attrelid) end
                  from pg_attribute a
                  join pg_class c on c.oid=a.attrelid
                  join pg_namespace n on n.oid=c.relnamespace
                  left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                 where n.nspname='dbo' and c.relname='clientphone' and a.attname=?
                """, String.class, column);
    }

    private boolean indexPresent(String index) {
        return Integer.valueOf(1).equals(jdbc.queryForObject(
                "select case when to_regclass(?) is null then 0 else 1 end", Integer.class, "dbo." + index));
    }

    static String shape(String value) {
        if (value == null) return "";
        // Spaces, parentheses and letter case inside SQL literals are data, not formatting.
        // In particular removing a space from translate's alphabet changes phone normalization.
        var literals = java.util.regex.Pattern.compile("'(?:''|[^'])*'").matcher(value);
        value = literals.replaceAll(match -> "literal_" + java.util.HexFormat.of().formatHex(
                match.group().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "_end");
        return value.toLowerCase(Locale.ROOT)
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
