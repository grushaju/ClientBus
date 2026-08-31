package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.service.MessageProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageInboundFlowIntegrationTest
        extends AbstractIntegrationTest {

    @Autowired
    private MessageProcessingService messageProcessingService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Test
    void processInbound_createsMessageForExistingChannelAccount() {

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

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        UUID channelAccountId =
                channelAccount.getId();

        String externalMessageId =
                "message-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-external-id",
                        "client_username",
                        "+79990000003",
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Hello from integration test",
                        "{\"source\":\"integration-test\"}",
                        Instant.parse(
                                "2026-08-30T10:00:00Z"
                        )
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        request,
                        java.util.List.of()
                );

        var result =
                messageProcessingService.processInbound(
                        event
                );

        assertNotNull(result);
        assertNotNull(result.id());

        assertEquals(
                externalMessageId,
                result.externalId()
        );

        assertEquals(
                MessageType.TEXT,
                result.type()
        );

        assertEquals(
                "Hello from integration test",
                result.content()
        );
    }

    @Test
    void processInbound_sameExternalMessageId_doesNotCreateDuplicate() {

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

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        UUID channelAccountId =
                channelAccount.getId();

        String externalMessageId =
                "duplicate-test-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-external-id",
                        "client_username",
                        "+79990000003",
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Duplicate test message",
                        "{\"source\":\"integration-test\"}",
                        Instant.parse(
                                "2026-08-30T10:00:00Z"
                        )
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        request,
                        java.util.List.of()
                );

        var first =
                messageProcessingService.processInbound(
                        event
                );

        var second =
                messageProcessingService.processInbound(
                        event
                );

        assertNotNull(first);
        assertNotNull(second);

        assertEquals(
                first.id(),
                second.id()
        );

        assertEquals(
                externalMessageId,
                first.externalId()
        );

        assertEquals(
                externalMessageId,
                second.externalId()
        );
    }

}