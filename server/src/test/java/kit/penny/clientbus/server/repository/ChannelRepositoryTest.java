package kit.penny.clientbus.server.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChannelRepositoryTest extends AbstractIntegrationTest {

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

        assertNotNull(channel.getId());

        ChannelEntity loaded =
                channelRepository.findById(
                        channel.getId()
                ).orElseThrow();

        assertEquals(
                channel.getId(),
                loaded.getId()
        );

        assertEquals(
                ChannelType.TELEGRAM,
                loaded.getType()
        );

        assertEquals(
                "Test TG Channel",
                loaded.getName()
        );

        assertEquals(
                workspace.getId(),
                loaded.getWorkspace().getId()
        );
    }

    @Test
    void findAllByWorkspaceId_returnsChannelsOfWorkspace() {

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

        ChannelEntity telegram =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.TELEGRAM,
                                "Telegram"
                        )
                );

        ChannelEntity whatsapp =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.WHATSAPP,
                                "WhatsApp"
                        )
                );

        List<ChannelEntity> channels =
                channelRepository.findAllByWorkspaceId(
                        workspace.getId()
                );

        assertEquals(
                2,
                channels.size()
        );

        assertTrue(
                channels.stream()
                        .anyMatch(
                                channel ->
                                        channel.getId()
                                                .equals(telegram.getId())
                        )
        );

        assertTrue(
                channels.stream()
                        .anyMatch(
                                channel ->
                                        channel.getId()
                                                .equals(whatsapp.getId())
                        )
        );
    }

    @Test
    void findAllByWorkspaceIdAndType_returnsChannelsOfRequestedType() {

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

        channelRepository.saveAndFlush(
                TestDataFactory.channel(
                        workspace,
                        ChannelType.TELEGRAM,
                        "Telegram"
                )
        );

        channelRepository.saveAndFlush(
                TestDataFactory.channel(
                        workspace,
                        ChannelType.WHATSAPP,
                        "WhatsApp"
                )
        );

        List<ChannelEntity> telegramChannels =
                channelRepository.findAllByWorkspaceIdAndType(
                        workspace.getId(),
                        ChannelType.TELEGRAM
                );

        assertEquals(
                1,
                telegramChannels.size()
        );

        assertEquals(
                ChannelType.TELEGRAM,
                telegramChannels.getFirst().getType()
        );

        assertEquals(
                "Telegram",
                telegramChannels.getFirst().getName()
        );
    }
}