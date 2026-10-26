package kit.penny.clientbus.server.connector.telegram;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.server.connector.ClientAccountAvatar;
import kit.penny.clientbus.server.connector.DiscoveredClientAccount;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.server.connector.command.ReadClientAccountAvatarCommand;
import kit.penny.clientbus.server.connector.command.SyncClientAccountCommand;
import kit.penny.clientbus.server.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramClientAccountDiscoveryTest {
    private static final UUID CHANNEL_ACCOUNT = UUID.randomUUID();
    private static final long USER_ID = 123456L;
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 42};

    @Mock private TelegramClientManager manager;
    @Mock private TelegramClient client;
    @Mock private ConfigurableApplicationContext applicationContext;
    @Mock private TelegramUserService users;
    @Mock private IAttachmentStorage attachments;
    @TempDir Path directory;

    private TelegramChannelConnector connector;
    private TdApi.User user;

    @BeforeEach
    void setUp() {
        connector = new TelegramChannelConnector(manager, mock(TelegramConversationMapper.class),
                mock(TelegramAccountProfileSynchronizer.class), attachments);
        when(manager.require(CHANNEL_ACCOUNT)).thenReturn(new TelegramClientContext(CHANNEL_ACCOUNT,
                applicationContext, client, null));
        user = new TdApi.User();
        user.id = USER_ID;
        user.firstName = " Ivan ";
        user.lastName = " Ivanov ";
        user.phoneNumber = "70001234567";
        user.usernames = new TdApi.Usernames();
        user.usernames.activeUsernames = new String[]{"authoritative_name"};
    }

    @Test
    void phoneLookupReturnsAuthoritativeProfileAndSmallTemporaryPhoto() throws Exception {
        stubPhone();
        addPhoto();
        stubDownload(temporaryFile(JPEG));

        DiscoveredClientAccount result = connector.handle(new DiscoverClientAccountCommand(CHANNEL_ACCOUNT, " +70001234567 ", null));

        verify(users).searchUserByPhoneNumber("+70001234567");
        assertEquals(Long.toString(USER_ID), result.externalId());
        assertEquals("authoritative_name", result.username());
        assertEquals("70001234567", result.phone());
        assertEquals("Ivan Ivanov", result.displayName());
        assertEquals("image/jpeg", result.avatarPreview().contentType());
        assertArrayEquals(JPEG, Base64.getDecoder().decode(result.avatarPreview().base64()));
        ArgumentCaptor<TdApi.DownloadFile> download = ArgumentCaptor.forClass(TdApi.DownloadFile.class);
        verify(client).sendAsync(download.capture());
        assertEquals(11, download.getValue().fileId);
        assertEquals(TelegramClientAccountDiscovery.PREVIEW_MAX_BYTES + 1L, download.getValue().limit);
        assertTrue(download.getValue().synchronous);
        verifyNoInteractions(attachments);
    }

    @Test
    void usernameLookupNormalizesAtSignAndKeepsNoAvatarContact() {
        when(applicationContext.getBean(TelegramUserService.class)).thenReturn(users);
        when(users.searchUserByUsername("query_name")).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(user, null)));
        DiscoveredClientAccount result = connector.handle(new DiscoverClientAccountCommand(CHANNEL_ACCOUNT, null, " @query_name "));
        assertEquals("authoritative_name", result.username());
        assertNull(result.avatarPreview());
        verifyNoInteractions(client, attachments);
    }

    @Test
    void personalPhotoCanBePreviewedButCannotBePersisted() throws Exception {
        stubPhone();
        addPhoto();
        user.profilePhoto.isPersonal = true;
        stubDownload(temporaryFile(JPEG));
        assertNotNull(discover().avatarPreview());

        clearInvocations(client);
        stubGetUser();
        assertTrue(readAvatar().isEmpty());
        verify(client, never()).sendAsync(any(TdApi.DownloadFile.class));
        verifyNoInteractions(attachments);
    }

    @Test
    void fullPhotoFetchesBigAtStartAndReturnsDefensiveBytesWithoutStorage() throws Exception {
        addPhoto();
        stubGetUser();
        stubDownload(temporaryFile(JPEG));
        ClientAccountAvatar result = readAvatar().orElseThrow();
        assertEquals("image/jpeg", result.contentType());
        assertEquals("314", result.externalPhotoId());
        assertArrayEquals(JPEG, result.content());
        byte[] exposed = result.content();
        exposed[0] = 0;
        assertArrayEquals(JPEG, result.content());
        ArgumentCaptor<TdApi.DownloadFile> download = ArgumentCaptor.forClass(TdApi.DownloadFile.class);
        verify(client).sendAsync(download.capture());
        assertEquals(22, download.getValue().fileId);
        assertEquals(TelegramClientAccountDiscovery.AVATAR_MAX_BYTES + 1L, download.getValue().limit);
        assertTrue(download.getValue().synchronous);
        verifyNoInteractions(attachments);
    }

    @Test
    void fullPhotoWithoutProfileReturnsEmptyWithoutDownloading() {
        stubGetUser();
        assertTrue(readAvatar().isEmpty());
        verify(client, never()).sendAsync(any(TdApi.DownloadFile.class));
        verifyNoInteractions(attachments);
    }

    @Test
    void previewOversizedProviderMetadataSkipsDownloadAndKeepsContact() {
        stubPhone();
        addPhoto();
        user.profilePhoto.small.expectedSize = TelegramClientAccountDiscovery.PREVIEW_MAX_BYTES + 1;
        assertNull(discover().avatarPreview());
        verifyNoInteractions(client, attachments);
    }

    @Test
    void previewOversizedActualFileIsRejectedDespiteMissingProviderSize() throws Exception {
        stubPhone();
        addPhoto();
        stubDownload(temporaryFile(new byte[TelegramClientAccountDiscovery.PREVIEW_MAX_BYTES + 1]));
        assertNull(discover().avatarPreview());
        verifyNoInteractions(attachments);
    }

    @Test
    void fullOversizedPhotoDoesNotDownload() {
        addPhoto();
        user.profilePhoto.big.size = TelegramClientAccountDiscovery.AVATAR_MAX_BYTES + 1;
        stubGetUser();
        assertTrue(readAvatar().isEmpty());
        verify(client, never()).sendAsync(any(TdApi.DownloadFile.class));
    }

    @Test
    void unreadableDownloadedFileDoesNotHideFoundContact() {
        stubPhone();
        addPhoto();
        TdApi.File downloaded = downloadedFile(directory.resolve("missing.jpg"));
        stubDownload(downloaded);
        assertNull(discover().avatarPreview());
        verifyNoInteractions(attachments);
    }

    @Test
    void providerDownloadFailureDoesNotHideFoundContact() {
        stubPhone();
        addPhoto();
        when(client.sendAsync(any(TdApi.DownloadFile.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        assertNull(discover().avatarPreview());
        verifyNoInteractions(attachments);
    }

    @Test
    void incompleteDownloadDoesNotExposePartialFile() throws Exception {
        stubPhone();
        addPhoto();
        TdApi.File downloaded = temporaryFile(JPEG);
        downloaded.local.isDownloadingCompleted = false;
        stubDownload(downloaded);
        assertNull(discover().avatarPreview());
    }

    @Test
    void unrecognizedMimeDoesNotExposeNonImageContent() throws Exception {
        stubPhone();
        addPhoto();
        stubDownload(temporaryFile("<svg>not a raster photo</svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(discover().avatarPreview());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stalledPreviewUsesBoundedWaitAndKeepsContact() throws Exception {
        stubPhone();
        addPhoto();
        CompletableFuture<TdlibResponse<TdApi.File>> stalled = mock(CompletableFuture.class);
        when(stalled.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
        when(client.sendAsync(any(TdApi.DownloadFile.class))).thenReturn(stalled);
        assertNull(discover().avatarPreview());
        ArgumentCaptor<Long> wait = ArgumentCaptor.forClass(Long.class);
        verify(stalled).get(wait.capture(), eq(TimeUnit.NANOSECONDS));
        assertTrue(wait.getValue() > 0 && wait.getValue() <= TimeUnit.SECONDS.toNanos(3));
    }

    @Test
    void fullPhotoUserLookupFailureDoesNotPreventConversation() {
        when(client.sendAsync(any(TdApi.GetUser.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        assertTrue(readAvatar().isEmpty());
        verifyNoInteractions(attachments);
    }

    @Test
    void differentReturnedUserCannotSupplyAvatar() {
        user.id = USER_ID + 1;
        stubGetUser();
        assertTrue(readAvatar().isEmpty());
        verify(client, never()).sendAsync(any(TdApi.DownloadFile.class));
    }

    @Test
    void providerSearchErrorIsGenericAndDoesNotEchoContact() {
        when(applicationContext.getBean(TelegramUserService.class)).thenReturn(users);
        when(users.searchUserByPhoneNumber(anyString())).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(null,
                new TdApi.Error(400, "secret phone +70001234567"))));
        IllegalStateException error = assertThrows(IllegalStateException.class, this::discover);
        assertEquals("Telegram user search failed", error.getMessage());
        verifyNoInteractions(attachments);
    }

    @Test
    void absentSearchResultDoesNotCreateAnAccount() {
        when(applicationContext.getBean(TelegramUserService.class)).thenReturn(users);
        when(users.searchUserByPhoneNumber(anyString())).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(null, null)));
        assertThrows(EntityNotFoundException.class, this::discover);
        verifyNoInteractions(attachments);
    }

    @Test
    void syncProfileCannotPersistPersonalPhotoEither() {
        addPhoto();
        user.profilePhoto.isPersonal = true;
        when(client.send(any(TdApi.GetUser.class))).thenReturn(new TdlibResponse<>(user, null));
        assertNull(connector.handle(new SyncClientAccountCommand(CHANNEL_ACCOUNT, UUID.randomUUID(), Long.toString(USER_ID))).avatarUrl());
        verifyNoInteractions(attachments);
        verify(client, never()).send(any(TdApi.DownloadFile.class));
    }

    private DiscoveredClientAccount discover() {
        return connector.handle(new DiscoverClientAccountCommand(CHANNEL_ACCOUNT, "+70001234567", null));
    }

    private java.util.Optional<ClientAccountAvatar> readAvatar() {
        return connector.handle(new ReadClientAccountAvatarCommand(CHANNEL_ACCOUNT, Long.toString(USER_ID)));
    }

    private void stubPhone() {
        when(applicationContext.getBean(TelegramUserService.class)).thenReturn(users);
        when(users.searchUserByPhoneNumber(anyString())).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(user, null)));
    }

    private void stubGetUser() {
        when(client.sendAsync(any(TdApi.GetUser.class))).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(user, null)));
    }

    private void addPhoto() {
        user.profilePhoto = new TdApi.ProfilePhoto();
        user.profilePhoto.id = 314;
        user.profilePhoto.small = new TdApi.File();
        user.profilePhoto.small.id = 11;
        user.profilePhoto.big = new TdApi.File();
        user.profilePhoto.big.id = 22;
    }

    private void stubDownload(TdApi.File file) {
        when(client.sendAsync(any(TdApi.DownloadFile.class))).thenReturn(CompletableFuture.completedFuture(new TdlibResponse<>(file, null)));
    }

    private TdApi.File temporaryFile(byte[] bytes) throws Exception {
        Path path = directory.resolve(UUID.randomUUID() + ".jpg");
        Files.write(path, bytes);
        return downloadedFile(path);
    }

    private TdApi.File downloadedFile(Path path) {
        TdApi.File downloaded = new TdApi.File();
        downloaded.local = new TdApi.LocalFile();
        downloaded.local.path = path.toString();
        downloaded.local.isDownloadingCompleted = true;
        return downloaded;
    }
}
