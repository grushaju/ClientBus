package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MessageRepositoryIdempotencyTest
        extends AbstractIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private MessageEntity message;

    @BeforeEach
    void setUp() {

        OrganizationEntity organization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(
                                organization
                        )
                );

        ChannelEntity channel =
                channelRepository.save(
                        TestDataFactory.channel(
                                workspace
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.save(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.save(
                        TestDataFactory.clientAccount()
                );

        ConversationEntity conversation =
                conversationRepository.save(
                        TestDataFactory.conversation(
                                workspace,
                                channelAccount,
                                clientAccount
                        )
                );

        message =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message =
                messageRepository.saveAndFlush(
                        message
                );
    }

    @Test
    @Transactional
    void claimOutboundDelivery_shouldBeAcquiredOnlyOnce() {

        Instant attemptAt =
                Instant.parse(
                        "2026-09-30T10:00:00Z"
                );

        int firstClaim =
                messageRepository.claimOutboundDelivery(
                        message.getId(),
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.QUEUED,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.PROCESSING,
                        attemptAt
                );

        int secondClaim =
                messageRepository.claimOutboundDelivery(
                        message.getId(),
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.QUEUED,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.PROCESSING,
                        attemptAt.plusSeconds(1)
                );

        assertThat(firstClaim)
                .isEqualTo(1);

        assertThat(secondClaim)
                .isEqualTo(0);

        MessageEntity persisted =
                messageRepository
                        .findById(message.getId())
                        .orElseThrow();

        assertThat(
                persisted.getProcessingStatus()
        )
                .isEqualTo(
                        MessageProcessingStatus.PROCESSING
                );

        assertThat(
                persisted.getDeliveryStatus()
        )
                .isEqualTo(
                        MessageDeliveryStatus.PENDING
                );

        assertThat(
                persisted.getDeliveryAttemptAt()
        )
                .isEqualTo(attemptAt);

        assertThat(
                persisted.getExternalId()
        )
                .isNull();
    }

    @Test
    @Transactional
    void releaseOutboundDeliveryClaim_shouldReturnMessageToQueued() {

        Instant attemptAt =
                Instant.parse(
                        "2026-09-30T10:00:00Z"
                );

        int claimed =
                messageRepository.claimOutboundDelivery(
                        message.getId(),
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.QUEUED,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.PROCESSING,
                        attemptAt
                );

        assertThat(claimed)
                .isEqualTo(1);

        int released =
                messageRepository.releaseOutboundDeliveryClaim(
                        message.getId(),
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.PROCESSING,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.QUEUED
                );

        assertThat(released)
                .isEqualTo(1);

        MessageEntity persisted =
                messageRepository
                        .findById(message.getId())
                        .orElseThrow();

        assertThat(
                persisted.getProcessingStatus()
        )
                .isEqualTo(
                        MessageProcessingStatus.QUEUED
                );

        assertThat(
                persisted.getDeliveryStatus()
        )
                .isEqualTo(
                        MessageDeliveryStatus.PENDING
                );

        assertThat(
                persisted.getDeliveryAttemptAt()
        )
                .isNull();

        assertThat(
                persisted.getExternalId()
        )
                .isNull();
    }

    @Test
    @Transactional
    void claimOutboundDelivery_shouldNotClaimMessageWithExternalId() {

        message.setExternalId(
                "telegram-message-123"
        );

        message =
                messageRepository.saveAndFlush(
                        message
                );

        int claimed =
                messageRepository.claimOutboundDelivery(
                        message.getId(),
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.QUEUED,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.PROCESSING,
                        Instant.parse(
                                "2026-09-30T10:00:00Z"
                        )
                );

        assertThat(claimed)
                .isEqualTo(0);

        MessageEntity persisted =
                messageRepository
                        .findById(message.getId())
                        .orElseThrow();

        assertThat(
                persisted.getProcessingStatus()
        )
                .isEqualTo(
                        MessageProcessingStatus.QUEUED
                );

        assertThat(
                persisted.getExternalId()
        )
                .isEqualTo(
                        "telegram-message-123"
                );
    }
}