package kit.penny.clientbus.connector.telegram.account;

import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TelegramAccountProfileSynchronizer {

    private final HostChannelAccounts hostChannelAccounts;

    public TelegramAccountProfileSynchronizer(
            HostChannelAccounts hostChannelAccounts
    ) {
        this.hostChannelAccounts = hostChannelAccounts;
    }

    public void updateProfile(
            UUID channelAccountId,
            TdApi.User user,
            String avatarUrl
            ) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (user == null) {
            throw new IllegalArgumentException(
                    "Telegram user must not be null"
            );
        }

        hostChannelAccounts.updateProfile(
                channelAccountId,
                Long.toString(user.id),
                user.usernames != null
                        && user.usernames.activeUsernames != null
                        && user.usernames.activeUsernames.length > 0
                        ? user.usernames.activeUsernames[0]
                        : null,
                user.phoneNumber,
                buildDisplayName(
                        user.firstName,
                        user.lastName
                ),
                avatarUrl
        );
    }

    private String buildDisplayName(
            String firstName,
            String lastName
    ) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();

        if (first.isEmpty()) {
            return last.isEmpty() ? null : last;
        }

        if (last.isEmpty()) {
            return first;
        }

        return first + " " + last;
    }
}
