package kit.penny.clientbus.server.bootstrap;

import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Канон dbo.conversation и dbo.outboundsendcallback на ЧИСТОЙ базе (закрытие бэклога 19.11.2026).
 *
 * <p>Смысл проверки именно здесь, на реальном старте приложения: {@code V2__conversation_dialog_reference.sql}
 * выполняется до Hibernate и на чистой базе является no-op (все ветви охранены {@code to_regclass}), поэтому
 * частичные уникальные индексы свежим окружениям никогда не доставались — гонка создания диалога и уникальность
 * слота корреляции оставались на честном слове. Hibernate их выразить не может, их заводит
 * {@link CoreIndexBootstrap} после DDL.
 *
 * <p>Здесь нет {@code @Transactional}: проверка ценна ровно тем, что запись реально уходит в базу и реально
 * отбивается индексом, а это возможно только в собственной транзакции каждого вызова. Созданные строки
 * удаляются в {@link #cleanGraph()} — пробы помечены одним префиксом.
 */
@SpringBootTest
class CoreIndexBootstrapIntegrationTest extends AbstractIntegrationTest {

    /** Префикс-маркер всех пробных строк: по нему они и вычищаются. */
    private static final String PROBE = "core-index-bootstrap-" + UUID.randomUUID();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoreIndexBootstrap bootstrap;
    @Autowired private OrganizationRepository organizations;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private ChannelRepository channels;
    @Autowired private ChannelAccountRepository channelAccounts;
    @Autowired private ClientAccountRepository clientAccounts;
    @Autowired private ConversationRepository conversations;

    private OrganizationEntity organization;
    private WorkspaceEntity workspace;
    private ChannelEntity channel;
    private ChannelAccountEntity channelAccount;

    @AfterEach
    void cleanGraph() {
        jdbc.update("delete from dbo.conversation where dialogexternalid like ?", PROBE + "%");
        // Легаси-пробы создаются без ссылки диалога — по префиксу ссылки они не находятся.
        jdbc.update("""
                delete from dbo.conversation
                 where clientaccountid in (select clientaccountid from dbo.clientaccount where externalid like ?)
                """, PROBE + "%");
        jdbc.update("delete from dbo.outboundsendcallback where pendingexternalid like ?", PROBE + "%");
        if (channelAccount != null) {
            jdbc.update("delete from dbo.clientaccount where externalid like ?", PROBE + "%");
            jdbc.update("delete from dbo.channelaccount where channelaccountid = ?", channelAccount.getId());
            jdbc.update("delete from dbo.channel where channelid = ?", channel.getId());
            jdbc.update("delete from dbo.workspace where workspaceid = ?", workspace.getId());
            jdbc.update("delete from dbo.organization where organizationid = ?", organization.getId());
        }
        organization = null; workspace = null; channel = null; channelAccount = null;
        // Класс работает с одним общим контейнером: неверная форма индекса, оставленная одним тестом,
        // иначе валит соседний тест просто из-за порядка методов.
        jdbc.execute("drop index if exists dbo.conversation_channel_client_nodialog_uix");
        jdbc.execute(CoreIndexBootstrap.ONE_DIALOG_PER_PARTICIPANT.create());
    }

    /**
     * Индексы не просто «существуют», а существуют с теми самыми предикатами: определению сверяются,
     * потому что индекс без части {@code where} запретил бы второй диалог у участника, а это ровно то,
     * что 13.2b снимал. Легаси-ограничение 1:1 тоже не должно вернуться.
     */
    @Test
    void canonicalPartialIndexesExistOnAFreshDatabaseAfterStartup() {
        Map<String, String> definitions = new HashMap<>();
        for (var row : jdbc.queryForList("""
                select indexname, indexdef from pg_indexes
                 where schemaname='dbo' and indexname in (
                     'conversation_channel_client_dialog_uix','conversation_channel_client_nodialog_uix',
                     'outboundsendcallback_slot_uix','conversation_channel_client_uix')
                """)) {
            definitions.put((String) row.get("indexname"), ((String) row.get("indexdef")).toUpperCase(Locale.ROOT));
        }

        assertTrue(definitions.containsKey("conversation_channel_client_dialog_uix"),
                "чистая база осталась без уникальности диалога по внешней ссылке: " + definitions.keySet());
        assertTrue(definitions.get("conversation_channel_client_dialog_uix")
                        .contains("WHERE (DIALOGEXTERNALID IS NOT NULL)"),
                definitions.get("conversation_channel_client_dialog_uix"));

        assertTrue(definitions.containsKey("conversation_channel_client_nodialog_uix"),
                "чистая база осталась без легаси-ограничения 1:1: " + definitions.keySet());
        assertTrue(definitions.get("conversation_channel_client_nodialog_uix")
                        .contains("WHERE (DIALOGEXTERNALID IS NULL)"),
                definitions.get("conversation_channel_client_nodialog_uix"));

        assertTrue(definitions.containsKey("outboundsendcallback_slot_uix"),
                "чистая база осталась без уникальности слота корреляции: " + definitions.keySet());
        assertTrue(definitions.get("outboundsendcallback_slot_uix")
                        .contains("COALESCE(DIALOGEXTERNALID, RECIPIENTEXTERNALID)"),
                definitions.get("outboundsendcallback_slot_uix"));

        assertTrue(jdbc.queryForList("""
                        select 1 from pg_indexes where schemaname='dbo'
                         and indexname='conversation_channel_client_uix'
                        """).isEmpty(),
                "легаси-уникальность (канал, участник) без ссылки диалога вернулась бы и запретила второй диалог");
    }

    /**
     * Ограничение работает руками базы, а не кодом приложения: два диалога с одной внешней ссылкой
     * в одном аккаунте канала отбиваются индексом. Это ровно та гонка createConversationInternal,
     * на чистой базе раньше не блокировалась ничем.
     */
    @Test
    void duplicateDialogLinkIsRejectedByTheDatabaseItself() {
        graph();
        var firstClient = client(PROBE + "-a");
        var secondClient = client(PROBE + "-b");

        conversations.saveAndFlush(conversation(firstClient, PROBE + "-dialog"));
        var rival = conversation(secondClient, PROBE + "-dialog");

        assertThrows(DataIntegrityViolationException.class, () -> conversations.saveAndFlush(rival));
    }

    /**
     * Обратная сторона того же индекса: частичность обязана оставаться частичностью — у одного
     * участника может быть несколько диалогов, если ссылки разные (ради этого 13.2b и затевался).
     */
    @Test
    void oneParticipantKeepsSeveralDialogsWhileLinksDiffer() {
        graph();
        var client = client(PROBE + "-multi");

        conversations.saveAndFlush(conversation(client, PROBE + "-1"));
        conversations.saveAndFlush(conversation(client, PROBE + "-2"));

        assertEquals(2, jdbc.queryForObject(
                "select count(*) from dbo.conversation where clientaccountid = ?", Integer.class, client.getId()));
    }

    /**
     * Слот корреляции: повтор в тот же слот (канал, диалог или участник, pending id) отбивается,
     * а тот же pending id в другом диалоге — проходит: coalesce в ключе индекса не должен склеивать
     * диалоги между собой.
     */
    @Test
    void duplicateCorrelationSlotIsRejectedButAnotherDialogIsNot() {
        var account = UUID.randomUUID();
        var pending = PROBE + "-slot";
        callback(account, null, pending);

        assertThrows(DataIntegrityViolationException.class, () -> callback(account, null, pending));

        callback(account, PROBE + "-dialog", pending);
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from dbo.outboundsendcallback where pendingexternalid = ?",
                Integer.class, pending));
    }

    /**
     * Индекс с верным именем, но не той формы — это другой запрет, и проверка по имени пропустила бы
     * его молча. Сюда же попадает будущее изменение предиката: старые свежие базы обязаны получить
     * новую форму, а не остаться с старой под тем же именем.
     */
    @Test
    void canonicalIndexOfTheWrongShapeIsRebuiltOnTheNextStartup() {
        graph();
        var counterpart = client(PROBE + "-repair");
        conversations.saveAndFlush(conversation(counterpart, null));

        jdbc.execute("drop index dbo.conversation_channel_client_nodialog_uix");
        // Обычный индекс без уникальности и без предиката: имя каноническое, ограничения нет.
        jdbc.execute("create index conversation_channel_client_nodialog_uix"
                + " on dbo.conversation (channelaccountid, clientaccountid)");

        bootstrap.run(null);

        assertTrue(jdbc.queryForList("""
                        select indexdef from pg_indexes where schemaname='dbo'
                         and indexname='conversation_channel_client_nodialog_uix'
                        """, String.class).get(0).toUpperCase(Locale.ROOT)
                        .contains("WHERE (DIALOGEXTERNALID IS NULL)"));
        assertThrows(DataIntegrityViolationException.class,
                () -> conversations.saveAndFlush(conversation(counterpart, null)));
    }

    private void graph() {
        organization = organizations.saveAndFlush(TestDataFactory.organization());
        workspace = workspaces.saveAndFlush(TestDataFactory.workspace(organization));
        channel = channels.saveAndFlush(TestDataFactory.channel(workspace));
        channelAccount = channelAccounts.saveAndFlush(TestDataFactory.channelAccount(channel));
    }

    private ClientAccountEntity client(String externalId) {
        return clientAccounts.saveAndFlush(TestDataFactory.clientAccount(null, channel.getType(), externalId));
    }

    private ConversationEntity conversation(ClientAccountEntity client, String dialogExternalId) {
        ConversationEntity conversation = TestDataFactory.conversation(workspace, channelAccount, client);
        conversation.setDialogExternalId(dialogExternalId);

        return conversation;
    }

    private void callback(UUID channelAccountId, String dialogExternalId, String pendingExternalId) {
        jdbc.update("""
                insert into dbo.outboundsendcallback
                    (id, channelaccountid, recipientexternalid, dialogexternalid, pendingexternalid, createdat, retainuntil)
                values (?,?,?,?,?, clock_timestamp(), clock_timestamp() + interval '5 minutes')
                """,
                UUID.randomUUID(), channelAccountId, PROBE + "-recipient", dialogExternalId, pendingExternalId);
    }
}
