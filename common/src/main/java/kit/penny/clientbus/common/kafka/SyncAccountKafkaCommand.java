package kit.penny.clientbus.common.kafka;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SyncAccountKafkaCommand(

        @NotNull
        UUID channelAccountId

) {
}
