package kit.penny.clientbus.server.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ClientAccountRepositoryTest
        extends AbstractIntegrationTest {

    @Autowired
    private ClientAccountRepository repository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private WorkspaceEntity workspace;

    private ClientEntity client;

    @BeforeEach
    void setUp() {

        workspace =
                workspaceRepository.save(
                        TestDataFactory.workspace()
                );

        client =
                clientRepository.save(
                        TestDataFactory.client(
                                workspace
                        )
                );
    }

    @Test
    void findAllByClientId_shouldReturnAccounts() {

        ClientAccountEntity account =
                TestDataFactory.clientAccount(client);

        repository.save(account);

        List<ClientAccountEntity> result =
                repository.findAllByClientId(
                        client.getId()
                );

        assertThat(result)
                .hasSize(1);

        assertThat(result.get(0).getExternalId())
                .isEqualTo(account.getExternalId());
    }

    @Test
    void findAllByClientIsNull_shouldReturnUnassignedAccounts() {

        ClientAccountEntity assigned =
                TestDataFactory.clientAccount(client);

        ClientAccountEntity unassigned =
                TestDataFactory.unassignedAccount();

        repository.save(assigned);
        repository.save(unassigned);

        List<ClientAccountEntity> result =
                repository.findAllByClientIsNull();

        assertThat(result)
                .containsExactly(unassigned)
                .doesNotContain(assigned);
    }

    @Test
    void findAllByClientIsNullAndChannelType_shouldFilterByType() {

        ClientAccountEntity telegram =
                TestDataFactory.clientAccount(
                        null,
                        ChannelType.TELEGRAM,
                        "tg-1"
                );

        ClientAccountEntity whatsapp =
                TestDataFactory.clientAccount(
                        null,
                        ChannelType.WHATSAPP,
                        "wa-1"
                );

        repository.save(telegram);
        repository.save(whatsapp);

        List<ClientAccountEntity> result =
                repository.findAllByClientIsNullAndChannelType(
                        ChannelType.TELEGRAM
                );

        assertThat(result)
                .containsExactly(telegram);
    }

    @Test
    void findByChannelTypeAndExternalId_shouldFindAccount() {

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        "telegram-123"
                );

        repository.save(account);

        var result =
                repository.findByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                );

        assertThat(result)
                .isPresent();

        assertThat(
                result.get().getId()
        ).isEqualTo(account.getId());
    }

    @Test
    void existsByChannelTypeAndExternalId_shouldReturnTrue() {

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        client,
                        ChannelType.TELEGRAM,
                        "telegram-123"
                );

        repository.save(account);

        assertThat(
                repository.existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                )
        ).isTrue();
    }

    @Test
    void existsByChannelTypeAndExternalId_shouldReturnFalse() {

        assertThat(
                repository.existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "not-existing"
                )
        ).isFalse();
    }

    @Test
    void searchByClient_shouldFindByUsername() {

        ClientAccountEntity account =
                new ClientAccountEntity(
                        client,
                        ChannelType.TELEGRAM,
                        "tg-123",
                        "SuperUser",
                        "+79990000000",
                        "Ivan Petrov"
                );

        repository.save(account);

        List<ClientAccountEntity> result =
                repository.searchByClient(
                        client.getId(),
                        "superuser"
                );

        assertThat(result)
                .hasSize(1);

        assertThat(result.get(0).getId())
                .isEqualTo(account.getId());
    }

    @Test
    void searchByClient_shouldFindByPhone() {

        ClientAccountEntity account =
                new ClientAccountEntity(
                        client,
                        ChannelType.TELEGRAM,
                        "tg-456",
                        "ivan",
                        "+79991234567",
                        "Ivan"
                );

        repository.save(account);

        List<ClientAccountEntity> result =
                repository.searchByClient(
                        client.getId(),
                        "1234567"
                );

        assertThat(result)
                .hasSize(1);
    }

    @Test
    void searchByClient_shouldFindByDisplayName() {

        ClientAccountEntity account =
                new ClientAccountEntity(
                        client,
                        ChannelType.TELEGRAM,
                        "tg-789",
                        "ivan",
                        "+79990000000",
                        "John Smith"
                );

        repository.save(account);

        List<ClientAccountEntity> result =
                repository.searchByClient(
                        client.getId(),
                        "john"
                );

        assertThat(result)
                .hasSize(1);
    }

    @Test
    void searchByClient_shouldNotReturnOtherClientsAccounts() {

        ClientEntity anotherClient =
                clientRepository.save(
                        TestDataFactory.client(
                                workspace,
                                "Petr",
                                "Petrov"
                        )
                );

        ClientAccountEntity account =
                TestDataFactory.clientAccount(
                        anotherClient
                );

        repository.save(account);

        List<ClientAccountEntity> result =
                repository.searchByClient(
                        client.getId(),
                        "test"
                );

        assertThat(result)
                .isEmpty();
    }
}