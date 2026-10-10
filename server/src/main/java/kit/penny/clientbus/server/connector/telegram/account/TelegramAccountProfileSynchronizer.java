
package kit.penny.clientbus.server.connector.telegram.account;

import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TelegramAccountProfileSynchronizer {

    private final ChannelAccountRepository channelAccountRepository;

    public TelegramAccountProfileSynchronizer(
            ChannelAccountRepository channelAccountRepository
    ) {
        this.channelAccountRepository = channelAccountRepository;
    }

    public void updateProfile(UUID channelAccountId, TdApi.User user) {
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

        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Telegram channel account not found: "
                                        + channelAccountId
                        ));

        account.setExternalId(Long.toString(user.id));

        account.setUsername(
                user.usernames != null
                        && user.usernames.activeUsernames != null
                        && user.usernames.activeUsernames.length > 0
                        ? user.usernames.activeUsernames[0]
                        : null
        );

        account.setPhone(user.phoneNumber);
        account.setDisplayName(
                buildDisplayName(user.firstName, user.lastName)
        );

        channelAccountRepository.save(account);
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
