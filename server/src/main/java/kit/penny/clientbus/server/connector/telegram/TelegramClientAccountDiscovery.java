package kit.penny.clientbus.server.connector.telegram;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.server.connector.ClientAccountAvatar;
import kit.penny.clientbus.server.connector.ClientAccountAvatarPreview;
import kit.penny.clientbus.server.connector.DiscoveredClientAccount;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses only TDLib's download cache and bounded temporary byte arrays; never stores attachments. */
final class TelegramClientAccountDiscovery {
    static final int PREVIEW_MAX_BYTES = 256 * 1024;
    static final int AVATAR_MAX_BYTES = 5 * 1024 * 1024;
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PREVIEW_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration AVATAR_TIMEOUT = Duration.ofSeconds(5);

    private TelegramClientAccountDiscovery() {
    }

    static DiscoveredClientAccount discover(TelegramClientContext context, DiscoverClientAccountCommand command) {
        boolean hasPhone = command.phone() != null && !command.phone().isBlank();
        boolean hasUsername = command.username() != null && !command.username().isBlank();
        if (hasPhone == hasUsername) {
            throw new IllegalArgumentException("Exactly one of phone or username must be specified");
        }
        TelegramUserService users = context.applicationContext().getBean(TelegramUserService.class);
        CompletableFuture<TdlibResponse<TdApi.User>> lookup = hasPhone
                ? users.searchUserByPhoneNumber(command.phone().trim())
                : users.searchUserByUsername(normalizeUsername(command.username()));
        TdlibResponse<TdApi.User> response = await(lookup, deadline(LOOKUP_TIMEOUT));
        if (response.getError().isPresent()) {
            // Provider messages may contain the searched phone/username; do not copy them to logs or API errors.
            throw new IllegalStateException("Telegram user search failed");
        }
        TdApi.User user = response.getObject()
                .filter(found -> found.id > 0)
                .orElseThrow(() -> new EntityNotFoundException("Telegram user not found"));
        ClientAccountAvatarPreview preview = readPhoto(context.telegramClient(), user, true, deadline(PREVIEW_TIMEOUT))
                .map(photo -> new ClientAccountAvatarPreview(photo.contentType(), Base64.getEncoder().encodeToString(photo.content())))
                .orElse(null);
        return new DiscoveredClientAccount(Long.toString(user.id), username(user), emptyToNull(user.phoneNumber),
                displayName(user), preview);
    }

    static Optional<ClientAccountAvatar> readAvatar(TelegramClient client, long userId) {
        long deadline = deadline(AVATAR_TIMEOUT);
        try {
            TdApi.User user = await(client.sendAsync(new TdApi.GetUser(userId)), deadline).getObjectOrThrow();
            if (user == null || user.id != userId) return Optional.empty();
            return readPhoto(client, user, false, deadline);
        } catch (RuntimeException e) {
            // A missing/inaccessible/slow photo cannot prevent starting a conversation.
            return Optional.empty();
        }
    }

    private static Optional<ClientAccountAvatar> readPhoto(TelegramClient client, TdApi.User user,
                                                          boolean preview, long deadline) {
        TdApi.ProfilePhoto photo = user.profilePhoto;
        // Personal photos are visible only to this connected account and must not become a global profile image.
        if (photo == null || (!preview && photo.isPersonal)) return Optional.empty();
        TdApi.File source = preview ? photo.small : photo.big;
        int maxBytes = preview ? PREVIEW_MAX_BYTES : AVATAR_MAX_BYTES;
        if (source == null || source.id <= 0 || source.size > maxBytes || source.expectedSize > maxBytes) {
            return Optional.empty();
        }
        try {
            TdApi.File downloaded = await(client.sendAsync(new TdApi.DownloadFile(source.id, 32, 0, maxBytes + 1L, true)), deadline)
                    .getObjectOrThrow();
            if (downloaded == null || downloaded.local == null || !downloaded.local.isDownloadingCompleted
                    || downloaded.local.path == null || downloaded.local.path.isBlank()
                    || downloaded.size > maxBytes || downloaded.expectedSize > maxBytes) {
                return Optional.empty();
            }
            Path path = Path.of(downloaded.local.path);
            if (!Files.isRegularFile(path) || Files.size(path) > maxBytes) return Optional.empty();
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(maxBytes + 1);
            }
            if (bytes.length == 0 || bytes.length > maxBytes || System.nanoTime() > deadline) return Optional.empty();
            String contentType = imageContentType(bytes);
            if (contentType == null) return Optional.empty();
            return Optional.of(new ClientAccountAvatar(bytes, contentType, Long.toString(photo.id)));
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    private static String imageContentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static <T extends TdApi.Object> TdlibResponse<T> await(
            CompletableFuture<TdlibResponse<T>> future, long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram lookup interrupted");
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Telegram lookup unavailable");
        }
    }

    private static String normalizeUsername(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        if (normalized.isBlank()) throw new IllegalArgumentException("Username must not be blank");
        return normalized;
    }

    private static String username(TdApi.User user) {
        return user.usernames != null && user.usernames.activeUsernames != null && user.usernames.activeUsernames.length > 0
                ? emptyToNull(user.usernames.activeUsernames[0]) : null;
    }

    private static String displayName(TdApi.User user) {
        String first = user.firstName == null ? "" : user.firstName.trim();
        String last = user.lastName == null ? "" : user.lastName.trim();
        return emptyToNull((first + " " + last).trim());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
