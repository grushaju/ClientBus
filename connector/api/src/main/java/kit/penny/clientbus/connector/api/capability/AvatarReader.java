package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.model.ClientAccountAvatar;
import kit.penny.clientbus.connector.api.command.ReadClientAccountAvatarCommand;

import java.util.Optional;

/** Optional capability: fetch a real public profile photo of an external client account. */
public interface AvatarReader {

    default Optional<ClientAccountAvatar> handle(
            ReadClientAccountAvatarCommand command
    ) {
        return Optional.empty();
    }
}
