package kit.penny.clientbus.server.repository;

import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChannelAccountRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void saveAndFindById() {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(
                                organization
                        )
                );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace
                        )
                );

        ChannelAccountEntity account =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        assertNotNull(account.getId());

        ChannelAccountEntity loaded =
                channelAccountRepository.findById(
                        account.getId()
                ).orElseThrow();

        assertEquals(
                account.getId(),
                loaded.getId()
        );

        assertEquals(
                channel.getId(),
                loaded.getChannel().getId()
        );

        assertEquals(
                "channel-external-id",
                loaded.getExternalId()
        );

        assertEquals(
                "channel-username",
                loaded.getUsername()
        );

        assertEquals(
                "channel-phone",
                loaded.getPhone()
        );

        assertEquals(
                "channel-displayName",
                loaded.getDisplayName()
        );
    }

    @Test
    void findByChannelId_returnsChannelAccount() {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(
                                organization
                        )
                );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace
                        )
                );

        ChannelAccountEntity account =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-123",
                                "test_channel",
                                "+79990000000",
                                "Test Telegram Channel"
                        )
                );

        ChannelAccountEntity loaded =
                channelAccountRepository.findByChannelId(
                        channel.getId()
                ).orElseThrow();

        assertEquals(
                account.getId(),
                loaded.getId()
        );

        assertEquals(
                channel.getId(),
                loaded.getChannel().getId()
        );

        assertEquals(
                "telegram-123",
                loaded.getExternalId()
        );

        assertEquals(
                "test_channel",
                loaded.getUsername()
        );

        assertEquals(
                "+79990000000",
                loaded.getPhone()
        );

        assertEquals(
                "Test Telegram Channel",
                loaded.getDisplayName()
        );
    }

    @Test
    void findByChannelId_returnsEmptyForUnknownChannel() {

        UUID unknownChannelId =
                UUID.randomUUID();

        assertTrue(
                channelAccountRepository
                        .findByChannelId(
                                unknownChannelId
                        )
                        .isEmpty()
        );
    }
}
