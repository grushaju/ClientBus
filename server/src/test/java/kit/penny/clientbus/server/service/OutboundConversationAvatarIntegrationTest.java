package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.CreateOutboundConversationRequest;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.server.connector.command.ReadClientAccountAvatarCommand;
import kit.penny.clientbus.server.connector.telegram.authorization.TelegramAuthorizationService;
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real database boundaries with a connector and object storage under test control. */
@SpringBootTest(properties = {
        "clientbus.notifications.sse-enabled=false",
        "clientbus.notifications.history-consumer-enabled=false"
})
class OutboundConversationAvatarIntegrationTest extends AbstractIntegrationTest {
    @Autowired OutboundConversationService outbound;
    @Autowired TelegramAuthorizationService discovery;
    @Autowired ClientAccountRepository clientAccounts;
    @Autowired ClientAccountService inboundAccounts;
    @Autowired ConversationRepository conversations;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ConversationService conversationService;
    @MockitoBean ChannelConnectorRegistry registry;
    @MockitoBean(name = "attachmentStorage") IAttachmentStorage storage;

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private IChannelConnector connector;
    private static final byte[] FULL_PHOTO = png(0xff245588, 32);
    private static final byte[] PREVIEW_PHOTO = png(0xffaaccee, 4);

    record Fixture(UUID workspaceId, UUID channelAccountId, UserEntity user) {}

    @BeforeEach
    void storageAndConnector() throws Exception {
        objects.clear();
        deleted.clear();
        connector = mock(IChannelConnector.class);
        when(registry.getConnector(ChannelType.TELEGRAM)).thenReturn(connector);
        lenient().when(storage.store(any(InputStream.class), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "Object upload must not hold a database transaction");
                    byte[] bytes = ((InputStream) call.getArgument(0)).readAllBytes();
                    assertEquals(bytes.length, ((Number) call.getArgument(2)).longValue());
                    String key = "avatar-test/" + UUID.randomUUID();
                    objects.put(key, bytes);
                    return new StoredAttachmentMetadata(key, call.getArgument(1), call.getArgument(3), bytes.length);
                });
        lenient().when(storage.load(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Object comparison must not hold a database transaction");
            byte[] bytes = objects.get(call.getArgument(0));
            if (bytes == null) throw new IllegalStateException("Missing test object");
            return new ByteArrayInputStream(bytes);
        });
        lenient().doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Cleanup must run after database completion");
            String key = call.getArgument(0);
            deleted.add(key);
            objects.remove(key);
            return null;
        }).when(storage).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void searchByPhoneOrUsernameReturnsTemporaryPhotoWithoutPersistence(boolean byPhone) {
        Fixture fixture = fixture();
        String externalId = UUID.randomUUID().toString();
        String base64 = Base64.getEncoder().encodeToString(PREVIEW_PHOTO);
        when(connector.handle(any(DiscoverClientAccountCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            DiscoverClientAccountCommand command = call.getArgument(0);
            assertEquals(fixture.channelAccountId(), command.channelAccountId());
            assertEquals(byPhone ? "+79990000002" : null, command.phone());
            assertEquals(byPhone ? null : "test_contact", command.username());
            return new DiscoveredClientAccount(externalId, "test_contact", "+79990000002", "Test Contact",
                    new ClientAccountAvatarPreview("image/png", base64));
        });
        long accountCount = clientAccounts.count();
        long conversationCount = conversations.count();

        ClientAccountDiscoveryDto result = as(fixture.user(), () -> discovery.find(fixture.channelAccountId(),
                byPhone ? " +79990000002 " : null, byPhone ? null : " @test_contact "));

        assertEquals(externalId, result.externalId());
        assertNull(result.existingClientAccountId());
        assertNotNull(result.avatarPreview());
        assertEquals("image/png", result.avatarPreview().contentType());
        assertArrayEquals(PREVIEW_PHOTO, Base64.getDecoder().decode(result.avatarPreview().base64()));
        assertEquals(accountCount, clientAccounts.count());
        assertEquals(conversationCount, conversations.count());
        verifyNoInteractions(storage);
        verify(connector, never()).handle(any(ReadClientAccountAvatarCommand.class));
    }

    @Test
    void unauthorizedSearchDoesNotReadPhotoOrCreateStorageObjects() {
        Fixture owner = fixture();
        Fixture otherWorkspace = fixture();
        assertThrows(AccessDeniedException.class, () -> as(owner.user(), () ->
                discovery.find(otherWorkspace.channelAccountId(), null, "test_contact")));
        verifyNoInteractions(connector, storage);
    }

    @Test
    void membershipRevokedDuringLookupPreventsReturningThePreview() {
        Fixture fixture = fixture();
        String externalId = UUID.randomUUID().toString();
        when(connector.handle(any(DiscoverClientAccountCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            tx(() -> {
                var memberships = entityManager.createQuery("select ew from EmployeeWorkspaceEntity ew where ew.workspace.id = :workspace and ew.employee.user.id = :user", EmployeeWorkspaceEntity.class)
                        .setParameter("workspace", fixture.workspaceId()).setParameter("user", fixture.user().getId()).getResultList();
                assertEquals(1, memberships.size());
                memberships.forEach(entityManager::remove);
                return null;
            });
            return new DiscoveredClientAccount(externalId, "test_contact", "+79990000002", "Revoked contact",
                    new ClientAccountAvatarPreview("image/png", Base64.getEncoder().encodeToString(PREVIEW_PHOTO)));
        });

        assertThrows(AccessDeniedException.class, () -> as(fixture.user(), () ->
                discovery.find(fixture.channelAccountId(), null, "test_contact")));

        assertEquals(0, accountCount(externalId));
        verify(connector).handle(any(DiscoverClientAccountCommand.class));
        verifyNoInteractions(storage);
    }

    @Test
    void unauthorizedCreateIsRejectedBeforePhotoDownload() {
        Fixture owner = fixture();
        Fixture otherWorkspace = fixture();
        CreateOutboundConversationRequest request = request(otherWorkspace, UUID.randomUUID().toString());

        assertThrows(AccessDeniedException.class, () -> as(owner.user(), () -> outbound.create(request)));

        assertEquals(0, accountCount(request.externalId()));
        verifyNoInteractions(connector, storage);
    }

    @Test
    void createPersistsFullServerPhotoAndRepeatedCreateReusesIdenticalObject() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        fullPhoto();

        ConversationDto first = as(fixture.user(), () -> outbound.create(request));
        String firstKey = avatar(first.clientAccountId());
        assertNotNull(firstKey);
        assertArrayEquals(FULL_PHOTO, objects.get(firstKey));
        assertFalse(java.util.Arrays.equals(PREVIEW_PHOTO, objects.get(firstKey)));
        assertEquals(1, objects.size());

        ConversationDto second = as(fixture.user(), () -> outbound.create(request));

        assertEquals(first.id(), second.id());
        assertEquals(first.clientAccountId(), second.clientAccountId());
        assertEquals(firstKey, avatar(second.clientAccountId()));
        verify(storage, times(1)).store(any(InputStream.class), anyString(), anyLong(), anyString());
        verify(storage, never()).delete(anyString());
        assertEquals(1, accountCount(request.externalId()));
        assertEquals(1, conversationCount(first.clientAccountId()));
        verify(connector, times(2)).handle(new ReadClientAccountAvatarCommand(fixture.channelAccountId(), request.externalId()));
    }

    @Test
    void photoDownloadFailureDoesNotPreventCreationOrEraseExistingAvatar() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        String originalKey = "existing-avatar/" + UUID.randomUUID();
        UUID existingId = account(request.externalId(), originalKey);
        objects.put(originalKey, FULL_PHOTO);
        when(connector.handle(any(ReadClientAccountAvatarCommand.class)))
                .thenThrow(new CompletionException(new TimeoutException("Photo timed out")));

        ConversationDto created = as(fixture.user(), () -> outbound.create(request));

        assertEquals(existingId, created.clientAccountId());
        assertEquals(originalKey, avatar(created.clientAccountId()));
        assertArrayEquals(FULL_PHOTO, objects.get(originalKey));
        verifyNoInteractions(storage);
    }

    @Test
    void missingPhotoStillCreatesConversationWithoutAvatar() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        when(connector.handle(any(ReadClientAccountAvatarCommand.class))).thenReturn(Optional.empty());

        ConversationDto created = as(fixture.user(), () -> outbound.create(request));

        assertNull(avatar(created.clientAccountId()));
        assertEquals(1, conversationCount(created.clientAccountId()));
        verifyNoInteractions(storage);
    }

    @Test
    void storageFailureDoesNotPreventConversationCreation() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        fullPhoto();
        when(storage.store(any(InputStream.class), anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("Test storage unavailable"));

        ConversationDto created = as(fixture.user(), () -> outbound.create(request));

        assertNull(avatar(created.clientAccountId()));
        assertEquals(1, conversationCount(created.clientAccountId()));
        assertTrue(objects.isEmpty());
    }

    @Test
    void failedDatabaseCreateRollsBackAccountAndDeletesOnlyOwnNewFile() {
        Fixture fixture = fixture();
        String externalId = UUID.randomUUID().toString();
        CreateOutboundConversationRequest invalid = new CreateOutboundConversationRequest(fixture.workspaceId(),
                fixture.channelAccountId(), ChannelType.TELEGRAM, externalId, "contact", null, "x".repeat(260));
        fullPhoto();
        String untouchedKey = "unrelated-avatar/" + UUID.randomUUID();
        objects.put(untouchedKey, PREVIEW_PHOTO);

        assertThrows(RuntimeException.class, () -> as(fixture.user(), () -> outbound.create(invalid)));

        assertEquals(0, accountCount(externalId));
        assertEquals(Set.of(untouchedKey), objects.keySet());
        assertEquals(1, deleted.size());
        assertFalse(deleted.contains(untouchedKey));
    }

    @Test
    void afterCommitFailureDoesNotDeleteTheAlreadyReferencedAvatar() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        fullPhoto();
        IllegalStateException marker = new IllegalStateException("Injected afterCommit failure");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw marker; }
            });
            return invocation.callRealMethod();
        }).when(conversationService).createOutboundConversation(request);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> as(fixture.user(), () -> outbound.create(request)));

        assertSame(marker, failure);
        ClientAccountEntity persisted = clientAccounts.findByChannelTypeAndExternalId(ChannelType.TELEGRAM, request.externalId()).orElseThrow();
        assertEquals(1, accountCount(request.externalId()));
        assertEquals(1, conversationCount(persisted.getId()));
        assertNotNull(persisted.getAvatarUrl());
        assertArrayEquals(FULL_PHOTO, objects.get(persisted.getAvatarUrl()));
        assertEquals(Set.of(persisted.getAvatarUrl()), objects.keySet());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void disconnectDuringPhotoUploadRejectsCreateAndCleansItsFile() {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        fullPhoto();
        String key = "new-upload/" + UUID.randomUUID();
        when(storage.store(any(InputStream.class), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            byte[] bytes = ((InputStream) call.getArgument(0)).readAllBytes();
            objects.put(key, bytes);
            tx(() -> {
                ChannelAccountEntity account = entityManager.find(ChannelAccountEntity.class, fixture.channelAccountId());
                account.getChannel().setStatus(ChannelConnectionStatus.DISCONNECTED);
                return null;
            });
            return new StoredAttachmentMetadata(key, call.getArgument(1), call.getArgument(3), bytes.length);
        });

        assertThrows(ChannelUnavailableException.class, () -> as(fixture.user(), () -> outbound.create(request)));

        assertEquals(0, accountCount(request.externalId()));
        assertTrue(objects.isEmpty());
        assertEquals(Set.of(key), deleted);
    }

    @Test
    void concurrentCreatesCannotOverwriteWinnerAvatarAndDeleteOnlyUnusedUpload() throws Exception {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        String previousKey = "old-avatar/" + UUID.randomUUID();
        UUID accountId = account(request.externalId(), previousKey);
        objects.put(previousKey, PREVIEW_PHOTO);
        barrierPhoto();

        ConversationDto[] results = concurrent(fixture, request, fixture, request);

        assertEquals(results[0].id(), results[1].id());
        assertEquals(accountId, results[0].clientAccountId());
        String retainedKey = avatar(accountId);
        assertNotEquals(previousKey, retainedKey);
        assertArrayEquals(FULL_PHOTO, objects.get(retainedKey));
        assertEquals(Set.of(previousKey, retainedKey), objects.keySet());
        assertEquals(1, deleted.size());
        assertFalse(deleted.contains(previousKey));
        assertFalse(deleted.contains(retainedKey));
        assertEquals(1, conversationCount(accountId));
    }

    @Test
    void crossChannelConcurrentFirstCreatesRetryGlobalAccountConflictInFreshTransaction() throws Exception {
        Fixture first = fixture();
        Fixture second = secondChannel(first);
        String externalId = UUID.randomUUID().toString();
        CreateOutboundConversationRequest firstRequest = request(first, externalId);
        CreateOutboundConversationRequest secondRequest = request(second, externalId);
        barrierPhoto();

        ConversationDto[] results = concurrent(first, firstRequest, second, secondRequest);

        assertNotEquals(results[0].id(), results[1].id());
        assertEquals(results[0].clientAccountId(), results[1].clientAccountId());
        String retainedKey = avatar(results[0].clientAccountId());
        assertArrayEquals(FULL_PHOTO, objects.get(retainedKey));
        assertEquals(Set.of(retainedKey), objects.keySet());
        assertEquals(1, deleted.size());
        assertFalse(deleted.contains(retainedKey));
        assertEquals(1, accountCount(externalId));
        assertEquals(2, conversationCount(results[0].clientAccountId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inboundProfileCommitCannotRestoreStaleAvatarAfterOutboundCreatesConversation(boolean existingAvatar) throws Exception {
        Fixture fixture = fixture();
        CreateOutboundConversationRequest request = request(fixture, UUID.randomUUID().toString());
        String originalKey = existingAvatar ? "old-avatar/" + UUID.randomUUID() : null;
        UUID accountId = account(request.externalId(), originalKey);
        if (originalKey != null) objects.put(originalKey, PREVIEW_PHOTO);
        fullPhoto();

        CountDownLatch inboundProfileLoaded = new CountDownLatch(1);
        CountDownLatch allowInboundCommit = new CountDownLatch(1);
        String incomingName = "Incoming changed name";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> inbound = executor.submit(() -> tx(() -> {
                ClientAccountEntity staleAccount = inboundAccounts.getOrCreateForInbound(
                        ChannelType.TELEGRAM, request.externalId(), null, null, incomingName, null);
                assertEquals(accountId, staleAccount.getId());
                assertEquals(originalKey, staleAccount.getAvatarUrl());
                assertEquals(incomingName, staleAccount.getDisplayName());
                inboundProfileLoaded.countDown();
                try {
                    assertTrue(allowInboundCommit.await(15, TimeUnit.SECONDS), "Outbound must commit before inbound flush");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return null;
            }));

            assertTrue(inboundProfileLoaded.await(5, TimeUnit.SECONDS), "Inbound must read the previous avatar first");
            ConversationDto created = as(fixture.user(), () -> outbound.create(request));
            assertEquals(accountId, created.clientAccountId());
            String committedKey = avatar(accountId);
            assertNotNull(committedKey, "The full avatar must be committed before releasing inbound");
            assertNotEquals(originalKey, committedKey);
            assertArrayEquals(FULL_PHOTO, objects.get(committedKey));

            allowInboundCommit.countDown();
            inbound.get(10, TimeUnit.SECONDS);

            tx(() -> {
                ClientAccountEntity persisted = clientAccounts.findById(accountId).orElseThrow();
                assertEquals(committedKey, persisted.getAvatarUrl(), "An inbound profile change must retain the committed full avatar");
                assertEquals(incomingName, persisted.getDisplayName(), "The inbound profile change must still be committed");
                return null;
            });
            assertArrayEquals(FULL_PHOTO, objects.get(committedKey));
            assertEquals(1, conversationCount(accountId));
            assertTrue(deleted.isEmpty());
            assertEquals(existingAvatar ? Set.of(originalKey, committedKey) : Set.of(committedKey), objects.keySet());
        } finally {
            allowInboundCommit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void fullPhoto() {
        when(connector.handle(any(ReadClientAccountAvatarCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Connector download must not hold a database transaction");
            return Optional.of(new ClientAccountAvatar(FULL_PHOTO, "image/png", "test-photo"));
        });
    }

    private void barrierPhoto() {
        CyclicBarrier requestsPrepared = new CyclicBarrier(2);
        when(connector.handle(any(ReadClientAccountAvatarCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            requestsPrepared.await(10, TimeUnit.SECONDS);
            return Optional.of(new ClientAccountAvatar(FULL_PHOTO, "image/png", "test-photo"));
        });
    }

    private ConversationDto[] concurrent(Fixture first, CreateOutboundConversationRequest firstRequest,
                                         Fixture second, CreateOutboundConversationRequest secondRequest) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConversationDto> one = executor.submit(() -> as(first.user(), () -> outbound.create(firstRequest)));
            Future<ConversationDto> two = executor.submit(() -> as(second.user(), () -> outbound.create(secondRequest)));
            return new ConversationDto[]{one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)};
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Fixture fixture() {
        return tx(() -> {
            OrganizationEntity organization = TestDataFactory.organization();
            entityManager.persist(organization);
            WorkspaceEntity workspace = TestDataFactory.workspace(organization);
            entityManager.persist(workspace);
            UserEntity user = TestDataFactory.user("avatar-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com", "hash");
            entityManager.persist(user);
            EmployeeEntity employee = new EmployeeEntity(organization, user, "Avatar", "Employee", null);
            entityManager.persist(employee);
            entityManager.persist(new EmployeeWorkspaceEntity(employee, workspace));
            ChannelEntity channel = TestDataFactory.channel(workspace);
            channel.setStatus(ChannelConnectionStatus.CONNECTED);
            entityManager.persist(channel);
            ChannelAccountEntity channelAccount = TestDataFactory.channelAccount(channel);
            entityManager.persist(channelAccount);
            entityManager.flush();
            return new Fixture(workspace.getId(), channelAccount.getId(), user);
        });
    }

    private Fixture secondChannel(Fixture first) {
        return tx(() -> {
            ChannelEntity channel = TestDataFactory.channel(entityManager.getReference(WorkspaceEntity.class, first.workspaceId()));
            channel.setStatus(ChannelConnectionStatus.CONNECTED);
            entityManager.persist(channel);
            ChannelAccountEntity account = TestDataFactory.channelAccount(channel);
            entityManager.persist(account);
            entityManager.flush();
            return new Fixture(first.workspaceId(), account.getId(), first.user());
        });
    }

    private UUID account(String externalId, String avatarKey) {
        return tx(() -> {
            ClientAccountEntity account = TestDataFactory.clientAccount(null, ChannelType.TELEGRAM, externalId);
            account.setAvatarUrl(avatarKey);
            entityManager.persist(account);
            entityManager.flush();
            return account.getId();
        });
    }

    private String avatar(UUID id) {
        return tx(() -> clientAccounts.findById(id).orElseThrow().getAvatarUrl());
    }

    private long accountCount(String externalId) {
        return tx(() -> entityManager.createQuery("select count(a) from ClientAccountEntity a where a.channelType = :type and a.externalId = :id", Long.class)
                .setParameter("type", ChannelType.TELEGRAM).setParameter("id", externalId).getSingleResult());
    }

    private long conversationCount(UUID clientAccountId) {
        return tx(() -> entityManager.createQuery("select count(c) from ConversationEntity c where c.clientAccount.id = :id", Long.class)
                .setParameter("id", clientAccountId).getSingleResult());
    }

    private CreateOutboundConversationRequest request(Fixture fixture, String externalId) {
        return new CreateOutboundConversationRequest(fixture.workspaceId(), fixture.channelAccountId(), ChannelType.TELEGRAM,
                externalId, "test_contact", "+79990000002", "Test Contact");
    }

    private <T> T tx(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private <T> T as(UserEntity user, Supplier<T> operation) {
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static byte[] png(int color, int dimension) {
        try {
            BufferedImage image = new BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < dimension; y++) for (int x = 0; x < dimension; x++) image.setRGB(x, y, color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
