package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.MessageDto;

public record MessageCreationResult(
        MessageDto message,
        boolean existed
) {
}