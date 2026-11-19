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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CorePhoneColumnBootstrap bootstrap;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ClientRepository clients;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private OrganizationEntity organization;

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

    private static String shape(String value) {
        // Сравнение формы выражения берется у самого бутстрапа: тест обязан проверять ровно
        // тот компаратор, которым он сверяет колонку при старте, а не свой собственный.
        return CorePhoneColumnBootstrap.shape(value);
    }
}
