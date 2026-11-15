package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmConnectionEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmMasterEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.service.MessageProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 13.5 (живая приёмка DIKIDI, уточнение пользователя 15.11.2026): автор исходящего на
 * стороне салона — пользователь DIKIDI, мастер DIKIDI тоже пользователь платформы
 * (masters.info ключён по их user id), поэтому сотрудник берётся из СУЩЕСТВУЮЩЕГО
 * маппинга crm.master (externalid = id автора, employeeid = сотрудник): маппинг есть —
 * employee; мастер без маппинга или не мастер (суперадмин-владелец) — NULL.
 * Значения id/имён синтетические — живые значения приёмки в репозиторий не пишутся.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MasterMappingAttributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired MessageProcessingService messageProcessingService;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void outboundAuthorResolvesThroughExistingMasterMapping() {
        OrganizationEntity organization = TestDataFactory.organization();
        em.persist(organization);
        WorkspaceEntity workspace = TestDataFactory.workspace(organization);
        em.persist(workspace);
        CrmConnectionEntity connection = new CrmConnectionEntity();
        connection.setOrganization(organization); connection.setProvider(CrmType.DIKIDI);
        connection.setLogin("79000000000"); connection.setDisplayName("Тестовое подключение");
        connection.setSecretCiphertext(new byte[]{1}); connection.setSecretKeyId("test-key");
        connection.setStatus(CrmConnectionStatus.CONFIGURED);
        em.persist(connection);
        CrmProjectEntity project = new CrmProjectEntity();
        project.setOrganization(organization); project.setConnection(connection); project.setWorkspace(workspace);
        project.setProvider(CrmType.DIKIDI); project.setExternalId("777001"); project.setDisplayName("Тестовый проект");
        em.persist(project);
        ChannelEntity channel = new ChannelEntity(workspace, ChannelType.DIKIDI, "DIKIDI channel");
        em.persist(channel);
        ChannelAccountEntity account = TestDataFactory.channelAccount(channel, "777001", "test",
                "79000000000", "Тестовый проект");
        em.persist(account);
        UserEntity user = TestDataFactory.user();
        em.persist(user);
        EmployeeEntity employee = new EmployeeEntity(organization, user, "Мария", "Тестова", null);
        em.persist(employee);

        CrmMasterEntity mapped = new CrmMasterEntity(); // пользователь платформы = мастер = сотрудник
        mapped.setProject(project); mapped.setExternalId("700000001"); mapped.setDisplayName("Мастер Один");
        mapped.setEmployee(employee); mapped.setLastObservedAt(Instant.now());
        em.persist(mapped);
        CrmMasterEntity unmapped = new CrmMasterEntity(); // мастер, маппинг не выполнен
        unmapped.setProject(project); unmapped.setExternalId("700000002"); unmapped.setDisplayName("Мастер Два");
        unmapped.setLastObservedAt(Instant.now());
        em.persist(unmapped);

        messageProcessingService.processPlatformMessage(outbound(account.getId(), "880000001", "700000001"));
        assertEquals(employee.getId(), employeeOf("880000001"), "маппинг мастера задаёт автора сообщения");

        messageProcessingService.processPlatformMessage(outbound(account.getId(), "880000002", "700000002"));
        assertNull(employeeOf("880000002"), "мастер без маппинга — employee NULL, подстановка запрещена");

        messageProcessingService.processPlatformMessage(outbound(account.getId(), "880000003", "700000003"));
        assertNull(employeeOf("880000003"), "суперадмин-владелец не мастер — employee NULL");

        messageProcessingService.processPlatformMessage(new PlatformMessageRequest(
                account.getId(), "700000004", null, null, "Клиент Тест", "700000004", "880000004",
                MessageType.TEXT, "Хорошо, а что скажет ваш босс", null, Instant.now(), List.of(),
                kit.penny.clientbus.common.notification.EventOrigin.SYNC, "88550001", "700000004"));
        assertNull(employeeOf("880000004"), "входящее — от клиента, employee NULL");
    }

    private static PlatformMessageRequest outbound(UUID accountId, String externalId, String authorId) {
        return new PlatformMessageRequest(accountId, "700000004", null, null, "Клиент Тест", "777001", externalId,
                MessageType.TEXT, "Ответ салона", null, Instant.now(), List.of(),
                kit.penny.clientbus.common.notification.EventOrigin.SYNC, "88550001", authorId);
    }

    /** JDBC обходит очередь flush у JPA — принудительно выгружаем перед проверкой. */
    private UUID employeeOf(String externalMessageId) {
        em.flush();
        return jdbc.queryForObject("select employeeid from message where externalid = ? limit 1",
                UUID.class, externalMessageId);
    }
}
