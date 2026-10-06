package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.RecentChatsSyncRunEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.RecentChatsSyncRunRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecentChatsSyncRunServiceTest
        extends AbstractIntegrationTest {

    private static final Duration LEASE =
            Duration.ofMinutes(30);

    @Autowired
    private RecentChatsSyncRunService service;

    @Autowired
    private RecentChatsSyncRunRepository runRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void connectedWithoutRun_createsRun() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID runId = UUID.randomUUID();

        Optional<ChannelType> result =
                service.tryStart(
                        account.getId(),
                        runId,
                        LEASE
                );

        assertEquals(
                Optional.of(ChannelType.TELEGRAM),
                result
        );

        RecentChatsSyncRunEntity run =
                runRepository.findById(runId)
                        .orElseThrow();

        assertEquals(
                runId,
                run.getSyncRunId()
        );

        assertEquals(
                account.getId(),
                run.getChannelAccount().getId()
        );

        assertNotNull(run.getStartedAt());
        assertNotNull(run.getLeaseUntil());
        assertTrue(
                run.getLeaseUntil().isAfter(
                        run.getStartedAt()
                )
        );
    }

    @Test
    void connectedWithActiveRun_returnsEmpty() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID existingRunId =
                UUID.randomUUID();

        Instant startedAt =
                Instant.now().minusSeconds(30);

        RecentChatsSyncRunEntity existingRun =
                new RecentChatsSyncRunEntity(
                        existingRunId,
                        account,
                        startedAt,
                        startedAt.plus(LEASE)
                );

        runRepository.saveAndFlush(existingRun);

        UUID newRunId =
                UUID.randomUUID();

        Optional<ChannelType> result =
                service.tryStart(
                        account.getId(),
                        newRunId,
                        LEASE
                );

        assertTrue(result.isEmpty());

        assertTrue(
                runRepository.existsById(existingRunId)
        );

        assertFalse(
                runRepository.existsById(newRunId)
        );
    }

    @Test
    void disconnected_returnsEmpty() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        account.getChannel().setStatus(
                ChannelConnectionStatus.DISCONNECTED
        );

        channelRepository.saveAndFlush(
                account.getChannel()
        );

        UUID runId =
                UUID.randomUUID();

        Optional<ChannelType> result =
                service.tryStart(
                        account.getId(),
                        runId,
                        LEASE
                );

        assertTrue(result.isEmpty());

        assertFalse(
                runRepository.existsById(runId)
        );
    }

    @Test
    void expiredRun_deletesOldAndCreatesNewRun() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID oldRunId =
                UUID.randomUUID();

        Instant oldStartedAt =
                Instant.now().minus(Duration.ofHours(2));

        RecentChatsSyncRunEntity oldRun =
                new RecentChatsSyncRunEntity(
                        oldRunId,
                        account,
                        oldStartedAt,
                        oldStartedAt.plus(
                                Duration.ofMinutes(5)
                        )
                );

        runRepository.saveAndFlush(oldRun);

        UUID newRunId =
                UUID.randomUUID();

        Optional<ChannelType> result =
                service.tryStart(
                        account.getId(),
                        newRunId,
                        LEASE
                );

        assertEquals(
                Optional.of(ChannelType.TELEGRAM),
                result
        );

        assertFalse(
                runRepository.existsById(oldRunId)
        );

        RecentChatsSyncRunEntity newRun =
                runRepository.findById(newRunId)
                        .orElseThrow();

        assertEquals(
                account.getId(),
                newRun.getChannelAccount().getId()
        );

        long accountRunCount =
                runRepository.findAll()
                        .stream()
                        .filter(run ->
                                account.getId().equals(
                                        run.getChannelAccount().getId()
                                )
                        )
                        .count();

        assertEquals(
                1,
                accountRunCount
        );
    }

    @Test
    void completeCurrentRun_returnsTrue() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID runId =
                UUID.randomUUID();

        RecentChatsSyncRunEntity run =
                new RecentChatsSyncRunEntity(
                        runId,
                        account,
                        Instant.now(),
                        Instant.now().plus(LEASE)
                );

        runRepository.saveAndFlush(run);

        assertTrue(
                service.complete(
                        account.getId(),
                        runId
                )
        );

        assertFalse(
                runRepository.existsById(runId)
        );
    }

    @Test
    void completeOldRun_returnsFalse() {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID oldRunId =
                UUID.randomUUID();

        Instant expiredStartedAt =
                Instant.now().minus(Duration.ofHours(2));

        RecentChatsSyncRunEntity oldRun =
                new RecentChatsSyncRunEntity(
                        oldRunId,
                        account,
                        expiredStartedAt,
                        expiredStartedAt.plus(
                                Duration.ofMinutes(5)
                        )
                );

        runRepository.saveAndFlush(oldRun);

        UUID currentRunId =
                UUID.randomUUID();

        service.tryStart(
                account.getId(),
                currentRunId,
                LEASE
        );

        assertFalse(
                service.complete(
                        account.getId(),
                        oldRunId
                )
        );

        assertTrue(
                runRepository.existsById(currentRunId)
        );
    }

    private ChannelAccountEntity connectedTelegramAccount() {

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
                                workspace,
                                ChannelType.TELEGRAM,
                                "Test Telegram "
                                        + UUID.randomUUID()
                        )
                );

        channel.setStatus(
                ChannelConnectionStatus.CONNECTED
        );

        channelRepository.saveAndFlush(channel);

        ChannelAccountEntity account =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        return account;
    }
}