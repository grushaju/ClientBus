package kit.penny.clientbus.server.integration;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.channel.CreateChannelRequest;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChatCheckpointEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmConnectionEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ChatCheckpointRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.service.ChannelService;
import kit.penny.clientbus.server.service.DikidiChannelProvisioning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 13.5: жизненный цикл канала DIKIDI как проектной сущности — создание назначением
 * DIKIDI-проекта (поля строго из БД CRM), отзыв при отвязке (без переписки — удаление,
 * с перепиской — DISABLED с сохранением архива) и запрет ручного создания/удаления.
 */
@SpringBootTest
class DikidiProvisioningIntegrationTest extends AbstractIntegrationTest {
    @Autowired DikidiChannelProvisioning provisioning;
    @Autowired ChannelService channelService;
    @Autowired ChannelRepository channels;
    @Autowired ChatCheckpointRepository checkpoints;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    /** Проект собирается in-memory: провизионизация читает только поля CRM-строки. */
    private CrmProjectEntity project(CrmType provider) {
        return tx.execute(t -> {
            OrganizationEntity org = TestDataFactory.organization(); em.persist(org);
            WorkspaceEntity workspace = TestDataFactory.workspace(org); em.persist(workspace);
            CrmConnectionEntity connection = new CrmConnectionEntity();
            connection.setOrganization(org); connection.setProvider(provider);
            connection.setLogin("79000000000"); connection.setDisplayName("Иван Петров");
            connection.setSecretCiphertext(new byte[]{1}); connection.setSecretKeyId("test-key");
            connection.setStatus(kit.penny.clientbus.common.enums.CrmConnectionStatus.CONFIGURED);
            em.persist(connection);
            CrmProjectEntity project = new CrmProjectEntity();
            project.setOrganization(org); project.setConnection(connection); project.setWorkspace(workspace);
            project.setProvider(provider); project.setExternalId("777001"); project.setDisplayName("Студия X");
            em.persist(project); em.flush();
            return project;
        });
    }

    @Test
    void bindProvisionsChannelStrictlyFromCrmRowsAndRebindRefreshesProfile() {
        var project = project(CrmType.DIKIDI);
        provisioning.provisionOnBind(project);
        var channel = tx.execute(t -> channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI));
        assertEquals(1, channel.size());
        var row = jdbc.queryForMap("select ca.externalid, ca.phone, ca.username, ca.displayname, c.name, c.type "
                + "from dbo.channel c join dbo.channelaccount ca on ca.channelid=c.channelid where c.channelid=?",
                channel.get(0).getId());
        assertEquals("777001", row.get("externalid"), "company id проекта");
        assertEquals("Студия X", row.get("displayname"), "displayname проекта");
        assertEquals("79000000000", row.get("phone"), "login подключения — он же телефон");
        assertEquals("Иван Петров", row.get("username"), "displayname подключения");
        assertEquals("DIKIDI", row.get("type"));

        // повторное назначение (тот же проект, обновлённый профиль CRM) — без дубликата, профиль обновляется
        tx.executeWithoutResult(t -> {
            project.setDisplayName("Студия Y"); em.flush();
        });
        provisioning.provisionOnBind(project);
        assertEquals(1, channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).size());
        assertEquals("Студия Y", jdbc.queryForObject("select displayname from dbo.channelaccount where channelid=(select channelid from dbo.channel where channelid=?)",
                String.class, channel.get(0).getId()));
    }

    @Test
    void detachWithoutConversationsRemovesChannelAndCheckpoints() {
        var project = project(CrmType.DIKIDI);
        provisioning.provisionOnBind(project);
        var accountId = channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI)
                .get(0).getAccount().getId();
        tx.executeWithoutResult(t -> {
            var checkpoint = new ChatCheckpointEntity();
            checkpoint.setChannelAccountId(accountId); checkpoint.setDialogExternalId("77");
            checkpoint.setInitialSyncDone(true); checkpoint.setHistoryStartReached(true);
            checkpoints.saveAndFlush(checkpoint);
        });
        provisioning.retireOnDetach(project);
        assertEquals(0, channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.chatcheckpoint where channelaccountid=?",
                Integer.class, accountId));
    }

    @Test
    void detachWithConversationsKeepsArchiveAndDisablesChannel() {
        var project = project(CrmType.DIKIDI);
        provisioning.provisionOnBind(project);
        var channel = channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).get(0);
        tx.executeWithoutResult(t -> {
            var clientAccount = TestDataFactory.clientAccount(); em.persist(clientAccount);
            var conversation = TestDataFactory.conversation(project.getWorkspace(), channel.getAccount(), clientAccount);
            em.persist(conversation); em.flush();
        });
        provisioning.retireOnDetach(project);
        assertEquals(1, channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).size(),
                "переписка архивного проекта сохраняется вместе с каналом");
        assertEquals("DISABLED", jdbc.queryForObject("select status from dbo.channel where channelid=?",
                String.class, channel.getId()));
    }

    @Test
    void rebindAfterDisableReactivatesTheChannel() {
        var project = project(CrmType.DIKIDI);
        provisioning.provisionOnBind(project);
        var channel = channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).get(0);
        tx.executeWithoutResult(t -> em.find(ChannelEntity.class, channel.getId())
                .setStatus(ChannelConnectionStatus.DISABLED));
        provisioning.provisionOnBind(project);
        // намерение «подключить» заявлено (CONNECTING), флаг ожидаемого отключения снят
        assertEquals("CONNECTING", jdbc.queryForObject("select status from dbo.channel where channelid=?",
                String.class, channel.getId()));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("select notificationexpecteddisconnect from dbo.channel where channelid=?",
                Boolean.class, channel.getId())));
    }

    @Test
    void nonDikidiProjectsNeverProvisionOrRetire() {
        var project = project(CrmType.AMO);
        provisioning.provisionOnBind(project);
        provisioning.retireOnDetach(project);
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.channel c join dbo.workspace w on w.workspaceid=c.workspaceid where w.workspaceid=?",
                Integer.class, project.getWorkspace().getId()));
    }

    @Test
    void manualCreationOfDikidiChannelIsRejected() {
        var project = project(CrmType.DIKIDI);
        authenticateAsSuperAdmin(project.getWorkspace(), project.getOrganization());
        assertThrows(IllegalArgumentException.class, () -> channelService.createChannel(
                new CreateChannelRequest(project.getWorkspace().getId(), ChannelType.DIKIDI, "DIKIDI", null)));
        assertEquals(0, channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).size());
    }

    @Test
    void manualDeleteAndAccountRemovalOfProvisionedChannelAreRejected() {
        var project = project(CrmType.DIKIDI);
        provisioning.provisionOnBind(project);
        var channel = channels.findAllByWorkspaceIdAndType(project.getWorkspace().getId(), ChannelType.DIKIDI).get(0);
        authenticateAsSuperAdmin(project.getWorkspace(), project.getOrganization());
        assertThrows(IllegalArgumentException.class, () -> channelService.deleteChannel(channel.getId()));
        assertThrows(IllegalArgumentException.class, () -> channelService.disconnectChannelAccount(channel.getId()));
        assertTrue(channels.findById(channel.getId()).isPresent(), "канал живёт ровно столько, сколько проект");
    }

    private void authenticateAsSuperAdmin(WorkspaceEntity workspace, OrganizationEntity organization) {
        var principal = tx.execute(t -> {
            UserEntity user = TestDataFactory.user("it_" + UUID.randomUUID(), "it_" + UUID.randomUUID() + "@test.local", "$2a$10$test");
            user.setRole(UserRole.SUPER_ADMIN); em.persist(user);
            EmployeeEntity employee = new EmployeeEntity(organization, user, "IT", "Admin", null);
            em.persist(employee);
            em.persist(new EmployeeWorkspaceEntity(employee, workspace));
            em.flush();
            return new UserPrincipal(user);
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
