package kit.penny.clientbus.server.fixture;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.persistence.entity.*;

import java.util.UUID;

public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static OrganizationEntity organization() {

        return new OrganizationEntity(
                "Test organization  " + UUID.randomUUID()
        );
    }

    public static WorkspaceEntity workspace(OrganizationEntity organization) {

        return new WorkspaceEntity(organization,
                "Test Workspace " + UUID.randomUUID()
        );
    }

    public static WorkspaceEntity workspace(
            OrganizationEntity organization,
            String name
    ) {

        return new WorkspaceEntity(organization, name);
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
                "$2a$10$test",
                UserRole.EMPLOYEE
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
                passwordHash,
                UserRole.EMPLOYEE
        );
    }

    public static ChannelEntity channel(
            WorkspaceEntity workspace
    ) {
        return new ChannelEntity(
                workspace,
                ChannelType.TELEGRAM,
                "Test TG Channel"
        );
    }

    public static ChannelEntity channel(
            WorkspaceEntity workspace,
            ChannelType channelType,
            String name
    ) {
        return new ChannelEntity(
                workspace,
                channelType,
                name
        );
    }

    public static ChannelAccountEntity channelAccount(
            ChannelEntity channel
    ) {
        return new ChannelAccountEntity(
                channel,
                "channel-external-id",
                "channel-username",
                "channel-phone",
                "channel-displayName"
        );
    }

    public static ChannelAccountEntity channelAccount(
            ChannelEntity channel,
            String externalId,
            String username,
            String phone,
            String displayName
    ) {
        return new ChannelAccountEntity(
                channel,
                externalId,
                username,
                phone,
                displayName
        );
    }
}