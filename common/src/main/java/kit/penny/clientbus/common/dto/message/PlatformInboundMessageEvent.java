package kit.penny.clientbus.common.dto.message;

import java.util.List;

public record PlatformInboundMessageEvent(

        InboundMessageRequest message,

        List<PlatformInboundAttachment> attachments

) {
}