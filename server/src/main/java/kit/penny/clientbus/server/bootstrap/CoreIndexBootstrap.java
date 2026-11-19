package kit.penny.clientbus.server.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Частичные уникальные индексы dbo.conversation и dbo.outboundsendcallback, которых нет в
 * Hibernate-каноне (13.2b; закрытие бэклога 19.11.2026).
 *
 * <p>Причина, по которой одного Flyway не хватает: миграция выполняется ДО Hibernate, а бизнес-схему
 * на чистой БД создаёт Hibernate ПОСЛЕ Flyway. Поэтому каждая ветвь
 * {@code V2__conversation_dialog_reference.sql} охранена {@code to_regclass} и на чистой БД является
 * no-op — частичные индексы доставались только существующим базам. Свежие окружения (то есть почти все
 * текущие) жили без обоих ограничений: гонка {@code createConversationInternal} не блокировалась
 * ничем, а журнал корреляции раннего результата отправки мог принять два слота в один и тот же слот.
 * Hibernate выразить частичный индекс и индекс по выражению не может, поэтому канон доводится здесь —
 * тот же порядок, что у {@code CrmStorage}: после DDL, при каждом старте.
 *
 * <p>Миграцию это не дублирует вредно: {@code V2} уже применён на существующих базах, поэтому его текст
 * нельзя править даже ради комментария — Flyway сверяет контрольную сумму и падает на следующем старте.
 * Здешние {@code create ... if not exists} на такой базе ничего не делают. Чтобы тексты двух путей не
 * разъезжались, тест сверяет определение индекса, прочитанное из {@code pg_indexes}, с предикатами
 * миграции.
 *
 * <p>Дубликаты, из-за которых индекс не создаётся, не «чинятся» молча: как и в {@code crm.staffaccess},
 * старт падает с внятным сообщением. Молча оставить базу без ограничения — значит оставить ровно ту гонку,
 * ради устранения которой всё это заведено.
 */
@Component
public class CoreIndexBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CoreIndexBootstrap.class);

    /** Диалог уникален по внешней ссылке в пределах аккаунта канала. */
    static final String DIALOG_UNIQUE =
            "create unique index if not exists conversation_channel_client_dialog_uix"
                    + " on dbo.conversation (channelaccountid, dialogexternalid)"
                    + " where dialogexternalid is not null";

    /** Легаси-строки без ссылки: по-прежнему не более одного диалога на участника. */
    static final String ONE_DIALOG_PER_PARTICIPANT =
            "create unique index if not exists conversation_channel_client_nodialog_uix"
                    + " on dbo.conversation (channelaccountid, clientaccountid)"
                    + " where dialogexternalid is null";

    /**
     * Слот корреляции раннего результата: ссылка диалога, а при её отсутствии id участника (coalesce),
     * плюс pending id участника — он может повторяться в разных диалогах.
     */
    static final String CALLBACK_SLOT_UNIQUE =
            "create unique index if not exists outboundsendcallback_slot_uix"
                    + " on dbo.outboundsendcallback (channelaccountid,"
                    + " coalesce(dialogexternalid, recipientexternalid), pendingexternalid)";

    private final JdbcTemplate jdbc;

    public CoreIndexBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex(DIALOG_UNIQUE, "conversation_channel_client_dialog_uix", """
                select 1 from dbo.conversation where dialogexternalid is not null
                 group by channelaccountid, dialogexternalid having count(*) > 1 limit 1""",
                "dbo.conversation: у одного канала несколько диалогов с одинаковой внешней ссылкой — "
                        + "снесите дубликат (или проставьте dialogexternalid), иначе ограничение диалога не создать");
        ensureIndex(ONE_DIALOG_PER_PARTICIPANT, "conversation_channel_client_nodialog_uix", """
                select 1 from dbo.conversation where dialogexternalid is null
                 group by channelaccountid, clientaccountid having count(*) > 1 limit 1""",
                "dbo.conversation: у участника без внешней ссылки больше одного диалога — "
                        + "объедините их или проставьте dialogexternalid, иначе легаси-ограничение 1:1 не создать");
        ensureIndex(CALLBACK_SLOT_UNIQUE, "outboundsendcallback_slot_uix", """
                select 1 from dbo.outboundsendcallback
                 group by channelaccountid, coalesce(dialogexternalid, recipientexternalid), pendingexternalid
                having count(*) > 1 limit 1""",
                "dbo.outboundsendcallback: в один слот корреляции записано несколько ожиданий — "
                        + "очистите дублирующиеся строки (истёкшие удаляются retention'ом), иначе слот не уникален");
    }

    /**
     * Проверка на дубликаты стоит полного сканирования, поэтому она выполняется только когда индекса
     * действительно нет; на работающей базе путь — один дешёвый запрос к pg_indexes.
     */
    private void ensureIndex(String statement, String index, String duplicates, String remedy) {
        if (!jdbc.queryForList("select 1 from pg_indexes where schemaname='dbo' and indexname=?",
                String.class, index).isEmpty()) {
            return;
        }
        if (!jdbc.queryForList(duplicates, String.class).isEmpty()) {
            throw new IllegalStateException(remedy);
        }
        jdbc.execute(statement);
        log.info("Canonical index created: dbo.{}", index);
    }
}
