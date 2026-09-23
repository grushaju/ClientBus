package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ClientRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private UserRepository userRepository;

    private OrganizationEntity organization;
    private WorkspaceEntity workspace;
    private ClientEntity client;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(
                TestDataFactory.organization()
        );

        workspace = workspaceRepository.save(
                TestDataFactory.workspace(organization)
        );

        client = clientRepository.save(
                TestDataFactory.client(organization)
        );
    }

    @Test
    void findAllByOrganizationId_shouldReturnClientsFromOrganization() {
        ClientEntity secondClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Petr",
                                "Petrov"
                        )
                );

        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        ClientEntity anotherOrganizationClient =
                clientRepository.save(
                        TestDataFactory.client(
                                anotherOrganization,
                                "Other",
                                "Client"
                        )
                );

        List<ClientEntity> result =
                clientRepository.findAllByOrganizationId(
                        organization.getId()
                );

        assertThat(result)
                .containsExactlyInAnyOrder(
                        client,
                        secondClient
                )
                .doesNotContain(
                        anotherOrganizationClient
                );
    }

    @Test
    void findAllByOrganizationIdAndIsEnabledTrue_shouldReturnOnlyEnabledClients() {
        ClientEntity disabledClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Disabled",
                                "Client"
                        )
                );

        disabledClient.setEnabled(false);
        clientRepository.save(disabledClient);

        List<ClientEntity> result =
                clientRepository.findAllByOrganizationIdAndIsEnabledTrue(
                        organization.getId()
                );

        assertThat(result)
                .contains(client)
                .doesNotContain(disabledClient);
    }

    @Test
    void findAllByOrganizationIdAndIsEnabledFalse_shouldReturnOnlyDisabledClients() {
        client.setEnabled(false);
        clientRepository.save(client);

        ClientEntity enabledClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Enabled",
                                "Client"
                        )
                );

        List<ClientEntity> result =
                clientRepository.findAllByOrganizationIdAndIsEnabledFalse(
                        organization.getId()
                );

        assertThat(result)
                .contains(client)
                .doesNotContain(enabledClient);
    }

    @Test
    void findByOrganizationIdAndFirstNameContainingIgnoreCase_shouldFindClient() {
        client.setFirstName("Alexander");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository
                        .findByOrganizationIdAndFirstNameContainingIgnoreCase(
                                organization.getId(),
                                "xand"
                        );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void findByOrganizationIdAndLastNameContainingIgnoreCase_shouldFindClient() {
        client.setLastName("Smirnov");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository
                        .findByOrganizationIdAndLastNameContainingIgnoreCase(
                                organization.getId(),
                                "SMIR"
                        );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void findByOrganizationIdAndFirstNameAndLastName_shouldFindClient() {
        client.setFirstName("Alexander");
        client.setLastName("Smirnov");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository
                        .findByOrganizationIdAndFirstNameContainingIgnoreCaseAndLastNameContainingIgnoreCase(
                                organization.getId(),
                                "alex",
                                "SMIR"
                        );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void findByPhone_shouldFindClient() {
        List<ClientEntity> result =
                clientRepository.findByPhone("+79990000001")
                        .stream()
                        .toList();

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void findByPhoneAndOrganizationId_shouldRespectOrganization() {
        List<ClientEntity> result =
                clientRepository.findByPhoneAndOrganizationId(
                        "+79990000001",
                        organization.getId()
                ).stream().toList();

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void findByPhoneAndOrganizationId_shouldNotReturnClientFromAnotherOrganization() {
        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        ClientEntity anotherClient =
                TestDataFactory.client(
                        anotherOrganization
                );

        anotherClient.getPhoneList().clear();
        anotherClient.addPhone("+79991112233");

        anotherClient =
                clientRepository.save(anotherClient);

        assertThat(
                clientRepository.findByPhoneAndOrganizationId(
                        "+79991112233",
                        organization.getId()
                )
        ).isEmpty();

        assertThat(
                clientRepository.findByPhoneAndOrganizationId(
                        "+79991112233",
                        anotherOrganization.getId()
                )
        ).contains(anotherClient);
    }

    @Test
    void findByPhoneStartingWith_shouldFindMatchingPhones() {
        client.getPhoneList().clear();
        client.addPhone("+79991234567");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.findByPhoneStartingWith(
                        "+7999123"
                );

        assertThat(result)
                .contains(client);
    }

    @Test
    void searchClients_shouldFindByFirstName() {
        client.setFirstName("Alexander");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "alex"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchClients_shouldFindByLastName() {
        client.setLastName("Smirnov");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "smir"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchClients_shouldFindByClientPhone() {
        client.getPhoneList().clear();
        client.addPhone("+79995554433");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "5554433"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchClients_shouldFindByClientAccountUsername() {
        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        "telegram-search-" + UUID.randomUUID()
                );

        account.setUsername("unique_client_username");

        clientAccountRepository.save(account);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "CLIENT_USERNAME"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchClients_shouldFindByClientAccountPhone() {
        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        "telegram-search-phone-" + UUID.randomUUID()
                );

        account.setPhone("+79998887766");

        clientAccountRepository.save(account);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "8887766"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchClients_shouldNotFindByExternalId() {
        String externalId =
                "external-only-" + UUID.randomUUID();

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        externalId
                );

        clientAccountRepository.save(account);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        externalId
                );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void searchClients_shouldNotFindByDisplayName() {
        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        "display-name-" + UUID.randomUUID()
                );

        account.setDisplayName("UniqueDisplayName");

        clientAccountRepository.save(account);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "UniqueDisplayName"
                );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void searchClients_shouldNotReturnClientFromAnotherOrganization() {
        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        ClientEntity anotherClient =
                TestDataFactory.client(
                        anotherOrganization,
                        "Alexander",
                        "Smirnov"
                );

        anotherClient =
                clientRepository.save(anotherClient);

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "Alexander"
                );

        assertThat(result)
                .doesNotContain(anotherClient);
    }

    @Test
    void searchActiveClients_shouldFindEnabledClient() {
        client.setFirstName("ActiveSearchClient");
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.searchActiveClients(
                        organization.getId(),
                        "ActiveSearch"
                );

        assertThat(result)
                .containsExactly(client);
    }

    @Test
    void searchActiveClients_shouldExcludeDisabledClient() {
        client.setFirstName("DisabledSearchClient");
        client.setEnabled(false);
        clientRepository.save(client);

        List<ClientEntity> result =
                clientRepository.searchActiveClients(
                        organization.getId(),
                        "DisabledSearch"
                );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void findListAggregates_shouldReturnAccountCountAndLatestContact() {
        ClientAccountEntity firstAccount =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                client,
                                ChannelType.TELEGRAM,
                                "aggregate-1-" + UUID.randomUUID()
                        )
                );

        ClientAccountEntity secondAccount =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                client,
                                ChannelType.WHATSAPP,
                                "aggregate-2-" + UUID.randomUUID()
                        )
                );

        Instant firstContact =
                Instant.parse("2026-01-10T10:00:00Z");

        Instant latestContact =
                Instant.parse("2026-01-15T10:00:00Z");

        createConversation(
                workspace,
                firstAccount,
                "conversation-1",
                firstContact
        );

        createConversation(
                workspace,
                secondAccount,
                "conversation-2",
                latestContact
        );

        List<ClientRepository.ClientListAggregateProjection> result =
                clientRepository.findListAggregates(
                        organization.getId(),
                        List.of(client.getId())
                );

        assertThat(result)
                .hasSize(1);

        ClientRepository.ClientListAggregateProjection aggregate =
                result.getFirst();

        assertThat(aggregate.getClientId())
                .isEqualTo(client.getId());

        assertThat(aggregate.getAccountCount())
                .isEqualTo(2);

        assertThat(aggregate.getLastContactAt())
                .isEqualTo(latestContact);
    }

    @Test
    void findListAggregates_shouldReturnZeroAccountsAndNullLastContact() {
        List<ClientRepository.ClientListAggregateProjection> result =
                clientRepository.findListAggregates(
                        organization.getId(),
                        List.of(client.getId())
                );

        assertThat(result)
                .hasSize(1);

        ClientRepository.ClientListAggregateProjection aggregate =
                result.getFirst();

        assertThat(aggregate.getClientId())
                .isEqualTo(client.getId());

        assertThat(aggregate.getAccountCount())
                .isZero();

        assertThat(aggregate.getLastContactAt())
                .isNull();
    }

    @Test
    void findListAggregates_shouldIgnoreClientFromAnotherOrganization() {
        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        ClientEntity anotherClient =
                clientRepository.save(
                        TestDataFactory.client(
                                anotherOrganization
                        )
                );

        List<ClientRepository.ClientListAggregateProjection> result =
                clientRepository.findListAggregates(
                        organization.getId(),
                        List.of(
                                client.getId(),
                                anotherClient.getId()
                        )
                );

        assertThat(result)
                .extracting(
                        ClientRepository.ClientListAggregateProjection::getClientId
                )
                .containsExactly(client.getId());
    }

    @Test
    void searchClientsForEmployee_shouldFindByClientPhone() {
        WorkspaceEntity employeeWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Employee Workspace"
                        )
                );

        EmployeeEntity employee =
                createEmployee();

        employeeWorkspaceRepository.save(
                new EmployeeWorkspaceEntity(
                        employee,
                        employeeWorkspace
                )
        );

        ClientEntity employeeClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Search",
                                "Phone"
                        )
                );

        employeeClient.getPhoneList().clear();
        employeeClient.addPhone("+79998887766");

        clientRepository.save(employeeClient);

        ClientAccountEntity account =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                employeeClient,
                                ChannelType.TELEGRAM,
                                "employee-phone-" + UUID.randomUUID()
                        )
                );

        createConversation(
                employeeWorkspace,
                account,
                "employee-phone-conversation"
        );

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "8887766"
                );

        assertThat(result)
                .containsExactly(employeeClient);
    }

    @Test
    void searchClientsForEmployee_shouldFindByAccountUsername() {
        WorkspaceEntity employeeWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Employee Workspace"
                        )
                );

        EmployeeEntity employee =
                createEmployee();

        employeeWorkspaceRepository.save(
                new EmployeeWorkspaceEntity(
                        employee,
                        employeeWorkspace
                )
        );

        ClientEntity employeeClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Search",
                                "Username"
                        )
                );

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        employeeClient,
                        ChannelType.TELEGRAM,
                        "employee-username-" + UUID.randomUUID()
                );

        account.setUsername("VerySpecialUsername");

        clientAccountRepository.save(account);

        createConversation(
                employeeWorkspace,
                account,
                "employee-username-conversation"
        );

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "specialusername"
                );

        assertThat(result)
                .containsExactly(employeeClient);
    }

    @Test
    void searchClientsForEmployee_shouldFindByAccountPhone() {
        WorkspaceEntity employeeWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Employee Workspace"
                        )
                );

        EmployeeEntity employee =
                createEmployee();

        employeeWorkspaceRepository.save(
                new EmployeeWorkspaceEntity(
                        employee,
                        employeeWorkspace
                )
        );

        ClientEntity employeeClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Search",
                                "AccountPhone"
                        )
                );

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        employeeClient,
                        ChannelType.TELEGRAM,
                        "employee-account-phone-" + UUID.randomUUID()
                );

        account.setPhone("+79996664433");

        clientAccountRepository.save(account);

        createConversation(
                employeeWorkspace,
                account,
                "employee-account-phone-conversation"
        );

        List<ClientEntity> result =
                clientRepository.searchClients(
                        organization.getId(),
                        "6664433"
                );

        assertThat(result)
                .containsExactly(employeeClient);
    }


    @Test
    void findListAggregatesForEmployee_shouldAggregateOnlyAccessibleAccounts() {
        WorkspaceEntity employeeWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Employee Workspace"
                        )
                );

        WorkspaceEntity otherWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Other Workspace"
                        )
                );

        EmployeeEntity employee =
                createEmployee();

        employeeWorkspaceRepository.save(
                new EmployeeWorkspaceEntity(
                        employee,
                        employeeWorkspace
                )
        );

        ClientAccountEntity accessibleAccount =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                client,
                                ChannelType.TELEGRAM,
                                "accessible-" + UUID.randomUUID()
                        )
                );

        ClientAccountEntity inaccessibleAccount =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                client,
                                ChannelType.WHATSAPP,
                                "inaccessible-" + UUID.randomUUID()
                        )
                );

        Instant accessibleContact =
                Instant.parse("2026-01-10T10:00:00Z");

        Instant inaccessibleContact =
                Instant.parse("2026-01-20T10:00:00Z");

        createConversation(
                employeeWorkspace,
                accessibleAccount,
                "accessible-conversation",
                accessibleContact
        );

        createConversation(
                otherWorkspace,
                inaccessibleAccount,
                "inaccessible-conversation",
                inaccessibleContact
        );

        List<ClientRepository.ClientListAggregateProjection> result =
                clientRepository.findListAggregatesForEmployee(
                        organization.getId(),
                        employee.getId(),
                        List.of(client.getId())
                );

        assertThat(result)
                .hasSize(1);

        ClientRepository.ClientListAggregateProjection aggregate =
                result.getFirst();

        assertThat(aggregate.getAccountCount())
                .isEqualTo(1);

        assertThat(aggregate.getLastContactAt())
                .isEqualTo(accessibleContact);
    }

    @Test
    void existsVisibleToEmployee_shouldReturnTrueForAccessibleClient() {
        WorkspaceEntity employeeWorkspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization,
                                "Employee Workspace"
                        )
                );

        EmployeeEntity employee =
                createEmployee();

        employeeWorkspaceRepository.save(
                new EmployeeWorkspaceEntity(
                        employee,
                        employeeWorkspace
                )
        );

        ClientAccountEntity account =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount(
                                client,
                                ChannelType.TELEGRAM,
                                "visible-" + UUID.randomUUID()
                        )
                );

        createConversation(
                employeeWorkspace,
                account,
                "visible-conversation"
        );

        assertThat(
                clientRepository.existsVisibleToEmployee(
                        client.getId(),
                        organization.getId(),
                        employee.getId()
                )
        ).isTrue();
    }

    @Test
    void existsVisibleToEmployee_shouldReturnFalseForInaccessibleClient() {
        EmployeeEntity employee =
                createEmployee();

        assertThat(
                clientRepository.existsVisibleToEmployee(
                        client.getId(),
                        organization.getId(),
                        employee.getId()
                )
        ).isFalse();
    }

    @Test
    void existsByIdAndOrganizationId_shouldRespectOrganization() {
        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        ClientEntity anotherClient =
                clientRepository.save(
                        TestDataFactory.client(
                                anotherOrganization
                        )
                );

        assertThat(
                clientRepository.existsByIdAndOrganizationId(
                        client.getId(),
                        organization.getId()
                )
        ).isTrue();

        assertThat(
                clientRepository.existsByIdAndOrganizationId(
                        client.getId(),
                        anotherOrganization.getId()
                )
        ).isFalse();

        assertThat(
                clientRepository.existsByIdAndOrganizationId(
                        anotherClient.getId(),
                        organization.getId()
                )
        ).isFalse();
    }

    @Test
    void findClientsWithoutAccounts_shouldReturnOnlyClientsWithoutAccounts() {
        ClientEntity clientWithAccount =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "With",
                                "Account"
                        )
                );

        ClientEntity clientWithoutAccount =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Without",
                                "Account"
                        )
                );

        clientAccountRepository.save(
                TestDataFactory.clientAccount(
                        clientWithAccount
                )
        );

        List<ClientEntity> result =
                clientRepository.findClientsWithoutAccounts(
                        organization.getId()
                );

        assertThat(result)
                .contains(clientWithoutAccount)
                .doesNotContain(clientWithAccount);
    }

    @Test
    void countActiveClientsByOrganization_shouldCountEnabledClients() {
        ClientEntity secondClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Second",
                                "Client"
                        )
                );

        secondClient.setEnabled(false);
        clientRepository.save(secondClient);

        assertThat(
                clientRepository.countActiveClientsByOrganization(
                        organization.getId()
                )
        ).isEqualTo(1);
    }

    @Test
    void countDisabledClientsByOrganization_shouldCountDisabledClients() {
        client.setEnabled(false);
        clientRepository.save(client);

        ClientEntity enabledClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Enabled",
                                "Client"
                        )
                );

        assertThat(
                clientRepository.countDisabledClientsByOrganization(
                        organization.getId()
                )
        ).isEqualTo(1);

        assertThat(enabledClient.isEnabled())
                .isTrue();
    }

    @Test
    void countByOrganizationId_shouldCountOnlyOrganizationClients() {
        clientRepository.save(
                TestDataFactory.client(
                        organization,
                        "Second",
                        "Client"
                )
        );

        OrganizationEntity anotherOrganization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        clientRepository.save(
                TestDataFactory.client(
                        anotherOrganization,
                        "Other",
                        "Client"
                )
        );

        assertThat(
                clientRepository.countByOrganizationId(
                        organization.getId()
                )
        ).isEqualTo(2);
    }

    @Test
    void deleteAllByOrganizationId_shouldDeleteOnlyOrganizationClients() {
        ClientEntity anotherOrganizationClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organizationRepository.save(
                                        TestDataFactory.organization()
                                )
                        )
                );

        clientRepository.deleteAllByOrganizationId(
                organization.getId()
        );

        assertThat(
                clientRepository.findAllByOrganizationId(
                        organization.getId()
                )
        ).isEmpty();

        assertThat(
                clientRepository.findById(
                        anotherOrganizationClient.getId()
                )
        ).isPresent();
    }

    @Test
    void deleteAllByIds_shouldDeleteSpecifiedClients() {
        ClientEntity secondClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Second",
                                "Client"
                        )
                );

        ClientEntity thirdClient =
                clientRepository.save(
                        TestDataFactory.client(
                                organization,
                                "Third",
                                "Client"
                        )
                );

        clientRepository.deleteAllByIds(
                List.of(
                        client.getId(),
                        secondClient.getId()
                )
        );

        assertThat(
                clientRepository.findById(
                        client.getId()
                )
        ).isEmpty();

        assertThat(
                clientRepository.findById(
                        secondClient.getId()
                )
        ).isEmpty();

        assertThat(
                clientRepository.findById(
                        thirdClient.getId()
                )
        ).isPresent();
    }

    private EmployeeEntity createEmployee() {
        UserEntity user =
                userRepository.save(
                        TestDataFactory.user()
                );

        return employeeRepository.save(
                new EmployeeEntity(
                        organization,
                        user,
                        "Test",
                        "Employee",
                        "+79990000111"
                )
        );
    }

    private ConversationEntity createConversation(
            WorkspaceEntity workspace,
            ClientAccountEntity clientAccount,
            String externalId
    ) {
        return createConversation(
                workspace,
                clientAccount,
                externalId,
                Instant.now()
        );
    }

    private ConversationEntity createConversation(
            WorkspaceEntity workspace,
            ClientAccountEntity clientAccount,
            String externalId,
            Instant lastMessageAt
    ) {
        ChannelEntity channel =
                channelRepository.save(
                        TestDataFactory.channel(
                                workspace,
                                clientAccount.getChannelType(),
                                "Test Channel " + UUID.randomUUID()
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.save(
                        TestDataFactory.channelAccount(
                                channel,
                                externalId,
                                "channel_username_" + UUID.randomUUID(),
                                "+79990000099",
                                "Test Channel"
                        )
                );

        ConversationEntity conversation =
                TestDataFactory.conversation(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        conversation.setLastMessageAt(lastMessageAt);

        return conversationRepository.save(conversation);
    }
}