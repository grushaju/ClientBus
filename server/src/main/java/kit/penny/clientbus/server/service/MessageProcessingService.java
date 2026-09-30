package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.*;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.kafka.producer.IOutboundMessagePublisher;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MessageProcessingService
        implements IMessageProcessingService {

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final ClientAccountService clientAccountService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final MessageAttachmentService messageAttachmentService;
    private final MessageRepository messageRepository;
    private final IOutboundMessagePublisher outboundMessagePublisher;
    private final OutboundMessageTransactionService
            outboundMessageTransactionService;

    public MessageProcessingService(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            ClientAccountService clientAccountService,
            ConversationService conversationService,
            MessageService messageService,
            MessageAttachmentService messageAttachmentService,
            IOutboundMessagePublisher outboundMessagePublisher,
            MessageRepository messageRepository,
            OutboundMessageTransactionService
                    outboundMessageTransactionService
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.clientAccountService = clientAccountService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.messageAttachmentService = messageAttachmentService;
        this.outboundMessagePublisher = outboundMessagePublisher;
        this.messageRepository = messageRepository;
        this.outboundMessageTransactionService =
                outboundMessageTransactionService;
    }

    @Override
    @Transactional
    public MessageDto processPlatformMessage(
            PlatformMessageRequest request
    ) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "PlatformMessageRequest must not be null"
            );
        }

        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findById(request.channelAccountId())
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "ChannelAccount not found: "
                                                + request.channelAccountId()
                                )
                        );

        boolean outbound =
                channelAccount.getExternalId() != null
                        && channelAccount.getExternalId()
                        .equals(request.senderExternalId());

        ChannelType channelType =
                channelAccount
                        .getChannel()
                        .getType();

        ClientAccountEntity clientAccount =
                clientAccountService.getOrCreateForInbound(
                        channelType,
                        request.clientExternalId(),
                        request.clientUsername(),
                        request.clientPhone(),
                        request.clientDisplayName()
                );

        ConversationEntity conversation =
                conversationService.findEntityByAccounts(
                        channelAccount.getId(),
                        clientAccount.getId()
                );

        if (conversation == null) {
            conversation =
                    conversationService.createConversationInternal(
                            channelAccount,
                            clientAccount
                    );
        }

        MessageCreationResult result =
                messageService.createPlatformMessage(
                        new CreatePlatformMessageRequest(
                                conversation.getId(),
                                request.type(),
                                request.externalId(),
                                request.content(),
                                request.metadata(),
                                request.sentAt(),
                                outbound
                        )
                );

        if (!result.existed()) {

            MessageEntity messageEntity =
                    messageService.getMessageEntityForProcessing(
                            result.message().id()
                    );

            List<PlatformMessageAttachment> attachments =
                    request.attachments() == null
                            ? List.of()
                            : List.copyOf(
                            request.attachments()
                    );

            for (PlatformMessageAttachment attachment :
                    attachments) {

                if (attachment == null) {
                    throw new IllegalArgumentException(
                            "Platform message attachment must not be null"
                    );
                }

                messageAttachmentService
                        .createAttachmentFromStorage(
                                messageEntity,
                                attachment.type(),
                                attachment.storageKey(),
                                attachment.fileName(),
                                attachment.contentType(),
                                attachment.size()
                        );
            }
        }

        return result.message();
    }

    @Override
    @Transactional
    public void processPlatformConversation(
            PlatformConversationRequest request
    ) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "PlatformConversationRequest must not be null"
            );
        }

        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findById(request.channelAccountId())
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "ChannelAccount not found: "
                                                + request.channelAccountId()
                                )
                        );

        ChannelType channelType =
                channelAccount
                        .getChannel()
                        .getType();

        ClientAccountEntity clientAccount =
                clientAccountService.getOrCreateForInbound(
                        channelType,
                        request.clientExternalId(),
                        request.clientUsername(),
                        request.clientPhone(),
                        request.clientDisplayName()
                );

        ConversationEntity conversation =
                conversationService.findEntityByAccounts(
                        channelAccount.getId(),
                        clientAccount.getId()
                );

        boolean created = false;

        if (conversation == null) {

            conversation =
                    conversationService.createConversationInternal(
                            channelAccount,
                            clientAccount
                    );

            created = true;
        }

        conversationService.updateLastMessage(
                conversation,
                request.lastMessageAt(),
                request.lastMessagePreview()
        );

        /*
         * Snapshot unreadCount is authoritative only for a newly
         * created Conversation.
         *
         * For an existing Conversation the value may already have
         * been modified by realtime message processing. Replacing it
         * with an older snapshot could lose unread state.
         */
        if (created) {
            conversation.setUnreadCount(
                    request.unreadCount()
            );
        }
    }

    @Override
    public MessageDto processOutbound(
            OutboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {

        OutboundMessageTransactionService
                .OutboundMessageTransactionResult result =
                outboundMessageTransactionService.prepare(
                        request,
                        attachments
                );

        try {

            outboundMessagePublisher.publish(
                    result.channelType(),
                    result.command()
            );

            return result.message();

        } catch (RuntimeException e) {

            try {
                messageService.markDeliveryFailed(
                        result.messageId()
                );
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    @Override
    public MessageDto retryOutbound(
            UUID messageId
    ) {

        OutboundMessageTransactionService
                .OutboundMessageTransactionResult result =
                outboundMessageTransactionService.prepareRetry(
                        messageId
                );

        try {

            outboundMessagePublisher.publish(
                    result.channelType(),
                    result.command()
            );

            return result.message();

        } catch (RuntimeException e) {

            try {
                messageService.markDeliveryFailed(
                        result.messageId()
                );
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    @Override
    @Transactional
    public MessageDto forwardMessage(
            ForwardMessageRequest request
    ) {

        MessageEntity sourceMessage =
                messageService.getMessageEntity(
                        request.messageId()
                );

        ConversationEntity targetConversation;

        if (request.targetConversationId() != null) {

            targetConversation =
                    conversationService.findEntityForProcessing(
                            request.targetConversationId()
                    );

            conversationService.requireForwardTargetAccess(
                    targetConversation
            );

        } else {

            ClientAccountEntity clientAccount =
                    clientAccountRepository.findById(
                                    request.targetClientAccountId()
                            )
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Target ClientAccount not found: "
                                                    + request.targetClientAccountId()
                                    )
                            );

            ChannelAccountEntity channelAccount =
                    channelAccountRepository.findById(
                                    request.targetChannelAccountId()
                            )
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Target ChannelAccount not found: "
                                                    + request.targetChannelAccountId()
                                    )
                            );

            targetConversation =
                    conversationService.findOrCreateForForward(
                            channelAccount,
                            clientAccount
                    );
        }

        MessageDto forwarded =
                messageService.createForwardedMessage(
                        targetConversation,
                        sourceMessage
                );

        MessageEntity targetMessage =
                messageService.getMessageEntityForProcessing(
                        forwarded.id()
                );

        List<MessageAttachmentEntity> sourceAttachments =
                messageAttachmentService
                        .getAttachmentsForProcessing(
                                sourceMessage.getId()
                        );

        for (MessageAttachmentEntity sourceAttachment :
                sourceAttachments) {

            messageAttachmentService.createForwardedAttachment(
                    targetMessage,
                    sourceAttachment
            );
        }

        return forwarded;
    }

    @Override
    @Transactional
    public MessageDto processPlatformEvent(
            PlatformMessageEvent event
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "PlatformMessageEvent must not be null"
            );
        }

        MessageEntity message =
                messageRepository
                        .findByConversationChannelAccountIdAndExternalId(
                                event.channelAccountId(),
                                event.externalId()
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Message not found for "
                                                + "channelAccountId="
                                                + event.channelAccountId()
                                                + ", externalId="
                                                + event.externalId()
                                )
                        );

        return switch (event.type()) {

            case SENT ->
                    throw new IllegalStateException(
                            "SENT platform events are not processed "
                                    + "by the current asynchronous outbound flow"
                    );

            case DELIVERED ->
                    messageService.markDelivered(
                            message.getId()
                    );

            case READ ->
                    messageService.markRead(
                            message.getId()
                    );

            case FAILED ->
                    messageService.markDeliveryFailed(
                            message.getId()
                    );
        };
    }
}