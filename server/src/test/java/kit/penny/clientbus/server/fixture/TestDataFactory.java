package kit.penny.clientbus.server.fixture;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;

import java.util.UUID;

public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static WorkspaceEntity workspace() {

        return new WorkspaceEntity(
                "Test Workspace " + UUID.randomUUID()
        );
    }

    public static WorkspaceEntity workspace(
            String name
    ) {

        return new WorkspaceEntity(name);
    }

    public static ClientEntity client(
            WorkspaceEntity workspace
    ) {

        ClientEntity client =
                new ClientEntity(
                        "Ivan",
                        "Ivanov",
                        workspace
                );

        client.addPhone("+79990000001");

        return client;
    }

    public static ClientEntity client(
            WorkspaceEntity workspace,
            String firstName,
            String lastName
    ) {

        return new ClientEntity(
                firstName,
                lastName,
                workspace
        );
    }

    public static ClientAccountEntity clientAccount() {

        return new ClientAccountEntity(
                null,
                ChannelType.TELEGRAM,
                "tg-" + UUID.randomUUID(),
                "test_user",
                "+79990000002",
                "Test User"
        );
    }

    public static ClientAccountEntity clientAccount(
            ClientEntity client
    ) {

        return new ClientAccountEntity(
                client,
                ChannelType.TELEGRAM,
                "tg-" + UUID.randomUUID(),
                "test_user",
                "+79990000002",
                "Test User"
        );
    }

    public static ClientAccountEntity unassignedAccount() {

        return clientAccount(null);
    }

    public static ClientAccountEntity clientAccount(
            ClientEntity client,
            ChannelType channelType,
            String externalId
    ) {

        return new ClientAccountEntity(
                client,
                channelType,
                externalId,
                "test_user",
                "+79990000002",
                "Test User"
        );
    }

    public static UserEntity user() {

        return new UserEntity(
                "testuser_" + UUID.randomUUID(),
                "test@example.com",
                "$2a$10$test"
        );
    }

    public static UserEntity user(
            String username,
            String email,
            String passwordHash
    ) {

        return new UserEntity(
                username,
                email,
                passwordHash
        );
    }
}