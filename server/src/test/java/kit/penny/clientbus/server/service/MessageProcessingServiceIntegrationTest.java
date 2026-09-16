package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageProcessingServiceIntegrationTest
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

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @Test
    void processInbound_createsClientAccountConversationAndMessage() {

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
                                "Telegram inbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-001",
                                "company_channel",
                                "+79990000000",
                                "Company Telegram"
                        )
                );

        UUID channelAccountId =
                channelAccount.getId();

        String clientExternalId =
                "telegram-client-" + UUID.randomUUID();

        String externalMessageId =
                "telegram-message-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        clientExternalId,
                        "client_username",
                        "+79991112233",
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Hello from Telegram",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-30T14:00:00Z"
                        )
                );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(result);
        assertNotNull(result.id());

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        assertNotNull(clientAccount.getId());

        assertEquals(
                ChannelType.TELEGRAM,
                clientAccount.getChannelType()
        );

        assertEquals(
                clientExternalId,
                clientAccount.getExternalId()
        );

        assertEquals(
                "client_username",
                clientAccount.getUsername()
        );

        assertEquals(
                "+79991112233",
                clientAccount.getPhone()
        );

        assertEquals(
                "Test Client",
                clientAccount.getDisplayName()
        );

        assertNull(
                clientAccount.getClient()
        );

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccountId,
                                clientAccount.getId()
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        assertEquals(
                workspace.getId(),
                conversation.getWorkspace().getId()
        );

        assertEquals(
                channelAccountId,
                conversation.getChannelAccount().getId()
        );

        assertEquals(
                clientAccount.getId(),
                conversation.getClientAccount().getId()
        );

        MessageEntity message =
                messageRepository
                        .findById(result.id())
                        .orElseThrow();

        assertEquals(
                conversation.getId(),
                message.getConversation().getId()
        );

        assertEquals(
                MessageType.TEXT,
                message.getType()
        );

        assertEquals(
                "Hello from Telegram",
                message.getContent()
        );

        assertEquals(
                externalMessageId,
                message.getExternalId()
        );

        assertEquals(
                MessageProcessingStatus.RECEIVED,
                message.getProcessingStatus()
        );

        assertNull(
                message.getDeliveryStatus()
        );
    }

    @Test
    void processInbound_existingClientAccount_reusesIt() {

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
                                "Telegram inbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-002",
                                "company_channel",
                                "+79990000001",
                                "Company Telegram"
                        )
                );

        String clientExternalId =
                "telegram-existing-client-" + UUID.randomUUID();

        ClientAccountEntity existingClientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                );

        UUID existingClientAccountId =
                existingClientAccount.getId();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccount.getId(),
                        clientExternalId,
                        "new_username",
                        "+79991112244",
                        "New Display Name",
                        "telegram-message-" + UUID.randomUUID(),
                        MessageType.TEXT,
                        "Second message",
                        null,
                        Instant.parse(
                                "2026-08-30T14:01:00Z"
                        )
                );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(result);

        ClientAccountEntity loaded =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        assertEquals(
                existingClientAccountId,
                loaded.getId()
        );

        assertEquals(
                clientExternalId,
                loaded.getExternalId()
        );

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                existingClientAccountId
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        assertEquals(
                workspace.getId(),
                conversation.getWorkspace().getId()
        );
    }

    @Test
    void processInbound_existingMessage_isIdempotent() {

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
                                "Telegram idempotency"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-003",
                                "company_channel",
                                "+79990000002",
                                "Company Telegram"
                        )
                );

        String clientExternalId =
                "telegram-idempotent-client-" + UUID.randomUUID();

        String externalMessageId =
                "telegram-idempotent-message-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccount.getId(),
                        clientExternalId,
                        "client_username",
                        null,
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Idempotent message",
                        null,
                        Instant.parse(
                                "2026-08-30T14:02:00Z"
                        )
                );

        MessageDto first =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        MessageDto second =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(first);
        assertNotNull(second);

        assertEquals(
                first.id(),
                second.id()
        );

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                clientAccount.getId()
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        MessageEntity message =
                messageRepository
                        .findById(first.id())
                        .orElseThrow();

        assertEquals(
                conversation.getId(),
                message.getConversation().getId()
        );

        assertEquals(
                externalMessageId,
                message.getExternalId()
        );

        assertEquals(
                1,
                messageRepository.count()
        );
    }

    @Test
    void processInbound_missingChannelAccount_throwsException() {

        UUID unknownChannelAccountId =
                UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        unknownChannelAccountId,
                        "telegram-client-" + UUID.randomUUID(),
                        "client_username",
                        null,
                        "Test Client",
                        "telegram-message-" + UUID.randomUUID(),
                        MessageType.TEXT,
                        "Message for unknown channel",
                        null,
                        Instant.now()
                );

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () ->
                                messageProcessingService.processInbound(
                                        request,
                                        List.of()
                                )
                );

        assertEquals(
                "ChannelAccount not found: "
                        + unknownChannelAccountId,
                exception.getMessage()
        );

        assertEquals(
                0,
                clientAccountRepository.count()
        );

        assertEquals(
                0,
                conversationRepository.count()
        );

        assertEquals(
                0,
                messageRepository.count()
        );
    }

    @Test
    void outboundMessage_fullStateMachine_persistsAllTransitions() {

        MessageEntity message =
                createOutboundMessage();

        assertEquals(
                MessageProcessingStatus.QUEUED,
                reload(message.getId()).getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                reload(message.getId()).getDeliveryStatus()
        );

        assertNull(
                reload(message.getId()).getExternalId()
        );

        // QUEUED + PENDING -> PROCESSED + SENT
        messageService.markSent(
                message.getId(),
                "telegram-external-message-001"
        );

        MessageEntity sent =
                reload(message.getId());

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                sent.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                sent.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-001",
                sent.getExternalId()
        );

        // SENT -> DELIVERED
        messageService.markDelivered(
                message.getId()
        );

        MessageEntity delivered =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                delivered.getDeliveryStatus()
        );

        assertNotNull(
                delivered.getDeliveredAt()
        );

        // DELIVERED -> READ
        messageService.markRead(
                message.getId()
        );

        MessageEntity read =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                read.getDeliveryStatus()
        );

        assertNotNull(
                read.getReadAt()
        );

        assertNotNull(
                read.getDeliveredAt()
        );
    }

    @Test
    void outboundMessage_sentToFailed_persistsFailedState() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-002"
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                reload(message.getId()).getDeliveryStatus()
        );

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity failed =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failed.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-002",
                failed.getExternalId()
        );
    }

    @Test
    void outboundMessage_duplicateDelivered_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-003"
        );

        messageService.markDelivered(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        Instant firstDeliveredAt =
                first.getDeliveredAt();

        assertNotNull(firstDeliveredAt);

        messageService.markDelivered(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                second.getDeliveryStatus()
        );

        assertEquals(
                firstDeliveredAt,
                second.getDeliveredAt()
        );
    }

    @Test
    void outboundMessage_duplicateRead_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-004"
        );

        messageService.markDelivered(
                message.getId()
        );

        messageService.markRead(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        Instant firstReadAt =
                first.getReadAt();

        assertNotNull(firstReadAt);

        messageService.markRead(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                second.getDeliveryStatus()
        );

        assertEquals(
                firstReadAt,
                second.getReadAt()
        );
    }

    @Test
    void outboundMessage_duplicateFailed_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                first.getDeliveryStatus()
        );

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                second.getDeliveryStatus()
        );
    }

    @Test
    void retryOutbound_failedMessage_reusesExistingMessage() {

        MessageEntity message =
                createOutboundMessage();

        UUID messageId = message.getId();

        messageService.markDeliveryFailed(messageId);

        authenticate(
                createEmployeeForWorkspace(message)
        );

        MessageEntity failed =
                messageRepository.findById(messageId)
                        .orElseThrow();

        assertThat(failed.getProcessingStatus())
                .isEqualTo(MessageProcessingStatus.PROCESSED);

        assertThat(failed.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.FAILED);

        MessageDto result =
                messageProcessingService.retryOutbound(messageId);

        assertThat(result.id())
                .isEqualTo(messageId);

        MessageEntity retried =
                messageRepository.findById(messageId)
                        .orElseThrow();

        assertThat(retried.getId())
                .isEqualTo(messageId);

        assertThat(retried.getProcessingStatus())
                .isEqualTo(MessageProcessingStatus.QUEUED);

        assertThat(retried.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.PENDING);

        assertThat(
                messageRepository.findAll()
                        .stream()
                        .filter(m -> m.getId().equals(messageId))
                        .count()
        ).isEqualTo(1);
    }

    @Test
    void retryOutbound_failedMessage_publishesSameMessageToKafka() {

        MessageEntity message =
                createOutboundMessage();

        UUID messageId = message.getId();

        authenticate(
                createEmployeeForWorkspace(message)
        );

        // QUEUED -> PROCESSED + FAILED
        messageService.markDeliveryFailed(
                messageId
        );

        MessageEntity failed =
                reload(messageId);

        assertThat(failed.getProcessingStatus())
                .isEqualTo(MessageProcessingStatus.PROCESSED);

        assertThat(failed.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.FAILED);

        messageProcessingService.retryOutbound(
                messageId
        );

        MessageEntity queued =
                reload(messageId);

        assertThat(queued.getId())
                .isEqualTo(messageId);

        assertThat(queued.getProcessingStatus())
                .isEqualTo(MessageProcessingStatus.QUEUED);

        assertThat(queued.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.PENDING);
    }


    // =============================== //
    // ACL Tests
    // =============================== //

    @Test
    void retryOutbound_employeeWithWorkspaceAccess_isAllowed() {

        OrganizationEntity organization =
                createOrganization("Test Org");

        WorkspaceEntity workspace =
                createWorkspace(
                        "Test Workspace",
                        organization
                );

        EmployeeEntity employee =
                createEmployee(
                        "retry-employee",
                        "retry-employee@test.local",
                        UserRole.EMPLOYEE,
                        organization
                );

        assignEmployeeToWorkspace(
                employee,
                workspace
        );

        MessageEntity message =
                createOutboundMessage(workspace);

        messageService.markDeliveryFailed(
                message.getId()
        );

        authenticate(employee);

        assertDoesNotThrow(() ->
                messageProcessingService.retryOutbound(
                        message.getId()
                )
        );
    }

    @Test
    void retryOutbound_employeeWithoutWorkspaceAccess_isDenied() {

        OrganizationEntity organization =
                createOrganization("Test Org");

        WorkspaceEntity workspace =
                createWorkspace(
                        "Test Workspace",
                        organization
                );

        EmployeeEntity employee =
                createEmployee(
                        "retry-employee-no-access",
                        "retry-employee-no-access@test.local",
                        UserRole.EMPLOYEE,
                        organization
                );

        MessageEntity message =
                createOutboundMessage(workspace);

        messageService.markDeliveryFailed(
                message.getId()
        );

        authenticate(employee);

        assertThrows(
                AccessDeniedException.class,
                () ->
                        messageProcessingService.retryOutbound(
                                message.getId()
                        )
        );
    }

    @Test
    void retryOutbound_superAdminCanAccessWorkspaceInOwnOrganization() {

        OrganizationEntity organization =
                createOrganization("Test Org");

        WorkspaceEntity workspace =
                createWorkspace(
                        "Test Workspace",
                        organization
                );

        EmployeeEntity superAdmin =
                createEmployee(
                        "retry-super-admin",
                        "retry-super-admin@test.local",
                        UserRole.SUPER_ADMIN,
                        organization
                );

        MessageEntity message =
                createOutboundMessage(workspace);

        messageService.markDeliveryFailed(
                message.getId()
        );

        authenticate(superAdmin);

        assertDoesNotThrow(() ->
                messageProcessingService.retryOutbound(
                        message.getId()
                )
        );
    }

    @Test
    void retryOutbound_superAdminCannotAccessWorkspaceInAnotherOrganization() {

        OrganizationEntity organization =
                createOrganization("Test Org");

        OrganizationEntity anotherOrganization =
                createOrganization(
                        "Another Organization"
                );

        WorkspaceEntity anotherWorkspace =
                createWorkspace(
                        "Another Workspace",
                        anotherOrganization
                );

        EmployeeEntity superAdmin =
                createEmployee(
                        "retry-super-admin-other-org",
                        "retry-super-admin-other-org@test.local",
                        UserRole.SUPER_ADMIN,
                        organization
                );

        MessageEntity message =
                createOutboundMessage(
                        anotherWorkspace
                );

        messageService.markDeliveryFailed(
                message.getId()
        );

        authenticate(superAdmin);

        assertThrows(
                AccessDeniedException.class,
                () ->
                        messageProcessingService.retryOutbound(
                                message.getId()
                        )
        );
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void authenticate(
            EmployeeEntity employee
    ) {

        UserPrincipal principal =
                new UserPrincipal(
                        employee.getUser()
                );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        authentication
                );
    }

    private OrganizationEntity createOrganization(
            String name
    ) {

        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setName(name);

        return organizationRepository.save(
                organization
        );
    }

    private EmployeeEntity createEmployee(
            String username,
            String email,
            UserRole role,
            OrganizationEntity organization
    ) {

        UserEntity user =
                new UserEntity();

        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("password");
        user.setRole(role);
        user.setEnabled(true);

        user = userRepository.save(user);

        EmployeeEntity employee =
                new EmployeeEntity();

        employee.setUser(user);
        employee.setOrganization(organization);

        /*
         * EmployeeEntity требует эти поля.
         */
        employee.setFirstName(username);
        employee.setLastName("Test");

        return employeeRepository.save(
                employee
        );
    }

    private WorkspaceEntity createWorkspace(
            String name,
            OrganizationEntity organization
    ) {

        WorkspaceEntity workspace =
                new WorkspaceEntity();

        workspace.setName(name);
        workspace.setOrganization(organization);

        return workspaceRepository.save(
                workspace
        );
    }

    private void assignEmployeeToWorkspace(
            EmployeeEntity employee,
            WorkspaceEntity workspace
    ) {

        EmployeeWorkspaceEntity assignment =
                new EmployeeWorkspaceEntity();

        assignment.setEmployee(employee);
        assignment.setWorkspace(workspace);

        employeeWorkspaceRepository.save(
                assignment
        );
    }


    private MessageEntity createOutboundMessage() {

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
                                "Telegram outbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-outbound",
                                "company_channel",
                                "+79990000003",
                                "Company Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "telegram-client-outbound-"
                                        + UUID.randomUUID()
                        )
                );

        ConversationEntity conversation =
                conversationRepository.saveAndFlush(
                        TestDataFactory.conversation(
                                workspace,
                                channelAccount,
                                clientAccount
                        )
                );

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setClientAccount(null);
        message.setExternalId(null);
        message.setContent("Outbound test message");
        message.setMetadata(null);
        message.setSentAt(Instant.now());

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message =
                messageRepository.saveAndFlush(message);

        // RECEIVED -> PROCESSING
        messageService.startProcessing(
                message.getId()
        );

        // PROCESSING -> QUEUED
        messageService.markQueued(
                message.getId()
        );

        return reload(message.getId());
    }

    private EmployeeEntity createEmployeeForWorkspace(
            MessageEntity message
    ) {

        WorkspaceEntity workspace =
                message.getConversation()
                        .getWorkspace();

        EmployeeEntity employee =
                createEmployee(
                        "retry-test-"
                                + UUID.randomUUID(),
                        "retry-test-"
                                + UUID.randomUUID()
                                + "@test.local",
                        UserRole.EMPLOYEE,
                        workspace.getOrganization()
                );

        assignEmployeeToWorkspace(
                employee,
                workspace
        );

        return employee;
    }

    private MessageEntity createOutboundMessage(
            WorkspaceEntity workspace
    ) {

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.TELEGRAM,
                                "Telegram outbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-outbound-"
                                        + UUID.randomUUID(),
                                "company_channel",
                                "+79990000003",
                                "Company Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "telegram-client-outbound-"
                                        + UUID.randomUUID()
                        )
                );

        ConversationEntity conversation =
                conversationRepository.saveAndFlush(
                        TestDataFactory.conversation(
                                workspace,
                                channelAccount,
                                clientAccount
                        )
                );

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setClientAccount(null);
        message.setExternalId(null);
        message.setContent("Outbound test message");
        message.setMetadata(null);
        message.setSentAt(Instant.now());

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message =
                messageRepository.saveAndFlush(message);

        // RECEIVED -> PROCESSING
        messageService.startProcessing(
                message.getId()
        );

        // PROCESSING -> QUEUED
        messageService.markQueued(
                message.getId()
        );

        return reload(message.getId());
    }


    private MessageEntity reload(UUID messageId) {

        entityManager.flush();
        entityManager.clear();

        return messageRepository
                .findById(messageId)
                .orElseThrow();
    }
}