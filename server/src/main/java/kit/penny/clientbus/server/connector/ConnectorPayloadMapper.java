package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageAttachment;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.connector.api.model.ConnectorConversation;
import kit.penny.clientbus.connector.api.model.ConnectorInboundMessage;
import kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment;

import java.util.List;

/**
 * 1:1 field mapping between the neutral connector payloads and the host transport requests.
 * Transitional bridge of stage 13.2a: the wire shape and the processing pipeline stay unchanged on
 * both sides; connector implementations will produce connector payloads natively in stage 13.3.
 */
public final class ConnectorPayloadMapper {

    private ConnectorPayloadMapper() {
    }

    public static ConnectorConversation toConnector(PlatformConversationRequest request) {
        if (request == null) {
            return null;
        }
        return new ConnectorConversation(
                request.channelAccountId(),
                request.clientExternalId(),
                request.clientUsername(),
                request.clientPhone(),
                request.clientDisplayName(),
                request.lastMessageAt(),
                request.lastMessagePreview(),
                request.unreadCount(),
                request.avatarUrl(),
                request.dialogExternalId());
    }

    public static List<PlatformConversationRequest> toPlatformConversations(List<ConnectorConversation> conversations) {
        if (conversations == null) {
            return null;
        }
        return conversations.stream().map(ConnectorPayloadMapper::toPlatform).toList();
    }

    public static PlatformConversationRequest toPlatform(ConnectorConversation conversation) {
        if (conversation == null) {
            return null;
        }
        return new PlatformConversationRequest(
                conversation.channelAccountId(),
                conversation.clientExternalId(),
                conversation.clientUsername(),
                conversation.clientPhone(),
                conversation.clientDisplayName(),
                conversation.lastMessageAt(),
                conversation.lastMessagePreview(),
                conversation.unreadCount(),
                conversation.avatarUrl(),
                conversation.dialogExternalId());
    }

    public static ConnectorInboundMessage toConnector(PlatformMessageRequest request) {
        if (request == null) {
            return null;
        }
        return new ConnectorInboundMessage(
                request.channelAccountId(),
                request.clientExternalId(),
                request.clientUsername(),
                request.clientPhone(),
                request.clientDisplayName(),
                request.senderExternalId(),
                request.externalId(),
                request.type(),
                request.content(),
                request.metadata(),
                request.sentAt(),
                request.attachments() == null
                        ? null
                        : request.attachments().stream().map(ConnectorPayloadMapper::toConnector).toList(),
                request.origin(),
                request.dialogExternalId());
    }

    public static List<ConnectorInboundMessage> toConnectorMessages(List<PlatformMessageRequest> requests) {
        if (requests == null) {
            return null;
        }
        return requests.stream().map(ConnectorPayloadMapper::toConnector).toList();
    }

    public static PlatformMessageRequest toPlatform(ConnectorInboundMessage message) {
        if (message == null) {
            return null;
        }
        return new PlatformMessageRequest(
                message.channelAccountId(),
                message.clientExternalId(),
                message.clientUsername(),
                message.clientPhone(),
                message.clientDisplayName(),
                message.senderExternalId(),
                message.externalId(),
                message.type(),
                message.content(),
                message.metadata(),
                message.sentAt(),
                message.attachments() == null
                        ? null
                        : message.attachments().stream().map(ConnectorPayloadMapper::toPlatform).toList(),
                message.origin(),
                message.dialogExternalId());
    }

    public static List<PlatformMessageRequest> toPlatformMessages(List<ConnectorInboundMessage> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream().map(ConnectorPayloadMapper::toPlatform).toList();
    }

    private static ConnectorMessageAttachment toConnector(PlatformMessageAttachment attachment) {
        return attachment == null
                ? null
                : new ConnectorMessageAttachment(attachment.type(), attachment.storageKey(),
                        attachment.fileName(), attachment.contentType(), attachment.size());
    }

    private static PlatformMessageAttachment toPlatform(ConnectorMessageAttachment attachment) {
        return attachment == null
                ? null
                : new PlatformMessageAttachment(attachment.type(), attachment.storageKey(),
                        attachment.fileName(), attachment.contentType(), attachment.size());
    }
}
