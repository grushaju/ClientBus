package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RecentChatsSyncRunConcurrencyIntegrationTest
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
    void concurrentTryStart_exactlyOneAcquired() throws Exception {

        ChannelAccountEntity account =
                connectedTelegramAccount();

        UUID runIdA =
                UUID.randomUUID();

        UUID runIdB =
                UUID.randomUUID();

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<Optional<ChannelType>> futureA =
                    executor.submit(() -> {
                        start.await();

                        return service.tryStart(
                                account.getId(),
                                runIdA,
                                LEASE
                        );
                    });

            Future<Optional<ChannelType>> futureB =
                    executor.submit(() -> {
                        start.await();

                        return service.tryStart(
                                account.getId(),
                                runIdB,
                                LEASE
                        );
                    });

            start.countDown();

            Optional<ChannelType> resultA =
                    futureA.get();

            Optional<ChannelType> resultB =
                    futureB.get();

            int acquired =
                    (resultA.isPresent() ? 1 : 0)
                            + (resultB.isPresent() ? 1 : 0);

            assertEquals(
                    1,
                    acquired
            );

            List<UUID> acquiredRunIds =
                    new java.util.ArrayList<>();

            if (resultA.isPresent()) {
                acquiredRunIds.add(runIdA);
            }

            if (resultB.isPresent()) {
                acquiredRunIds.add(runIdB);
            }

            assertEquals(
                    1,
                    acquiredRunIds.size()
            );

            List<UUID> accountRuns =
                    runRepository.findAll()
                            .stream()
                            .filter(run ->
                                    account.getId().equals(
                                            run.getChannelAccount().getId()
                                    )
                            )
                            .map(run ->
                                    run.getSyncRunId()
                            )
                            .toList();

            assertEquals(
                    1,
                    accountRuns.size()
            );

            assertEquals(
                    acquiredRunIds.get(0),
                    accountRuns.get(0)
            );

            assertTrue(
                    accountRuns.get(0).equals(runIdA)
                            || accountRuns.get(0).equals(runIdB)
            );

        } finally {
            executor.shutdownNow();
        }
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
                                "Concurrency Telegram "
                                        + UUID.randomUUID()
                        )
                );

        channel.setStatus(
                ChannelConnectionStatus.CONNECTED
        );

        channelRepository.saveAndFlush(channel);

        return channelAccountRepository.saveAndFlush(
                TestDataFactory.channelAccount(
                        channel
                )
        );
    }
}