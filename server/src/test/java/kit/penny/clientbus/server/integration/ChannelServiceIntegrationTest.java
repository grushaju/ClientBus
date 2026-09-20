package kit.penny.clientbus.server.integration;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.connector.ChannelAccountLifecycleRegistry;
import kit.penny.clientbus.server.connector.IChannelAccountLifecycle;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.service.ChannelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChannelServiceIntegrationTest
        extends AbstractIntegrationTest {

    @Autowired
    private ChannelService channelService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @MockitoBean
    private ChannelAccountLifecycleRegistry lifecycleRegistry;

    private IChannelAccountLifecycle telegramLifecycle;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {

        telegramLifecycle =
                mock(IChannelAccountLifecycle.class);

        when(lifecycleRegistry.getLifecycle(ChannelType.TELEGRAM))
                .thenReturn(telegramLifecycle);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteChannel_shouldDisconnectLifecycleAndDeleteChannel() {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(organization)
                );

        authenticateAsSuperAdmin(workspace);

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(workspace)
                );

        ChannelAccountEntity account =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(channel)
                );

        channel.setAccount(account);
        channelRepository.saveAndFlush(channel);

        UUID channelId = channel.getId();
        UUID accountId = account.getId();

        channelService.deleteChannel(channelId);

        verify(telegramLifecycle)
                .disconnect(accountId);

        entityManager.flush();
        entityManager.clear();

        assertFalse(
                channelRepository.findById(channelId).isPresent()
        );

        assertFalse(
                channelAccountRepository.findById(accountId).isPresent()
        );
    }

    @Test
    void deleteChannel_shouldNotDeleteChannelWhenDisconnectFails() {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(organization)
                );

        authenticateAsSuperAdmin(workspace);

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(workspace)
                );

        ChannelAccountEntity account =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(channel)
                );

        UUID channelId = channel.getId();
        UUID accountId = account.getId();

        RuntimeException exception =
                new RuntimeException(
                        "Telegram disconnect failed"
                );

        doThrow(exception)
                .when(telegramLifecycle)
                .disconnect(accountId);

        try {
            channelService.deleteChannel(channelId);
        } catch (RuntimeException e) {
            // expected
        }

        verify(telegramLifecycle)
                .disconnect(accountId);

        assertTrue(
                channelRepository.findById(channelId).isPresent()
        );

        assertTrue(
                channelAccountRepository.findById(accountId).isPresent()
        );
    }

    private void authenticateAsSuperAdmin(
            WorkspaceEntity workspace
    ) {

        UserEntity user =
                userRepository.saveAndFlush(
                        TestDataFactory.user(
                                "integration_" + UUID.randomUUID(),
                                "integration_" + UUID.randomUUID()
                                        + "@example.com",
                                "$2a$10$test"
                        )
                );

        user.setRole(UserRole.SUPER_ADMIN);
        user = userRepository.saveAndFlush(user);

        EmployeeEntity employee =
                employeeRepository.saveAndFlush(
                        new EmployeeEntity(
                                workspace.getOrganization(),
                                user,
                                "Integration",
                                "Employee",
                                "+79990000010"
                        )
                );

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(
                        employee,
                        workspace
                )
        );

        UserPrincipal principal =
                new UserPrincipal(user);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);
    }
}