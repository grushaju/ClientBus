package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.*;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.crm.CrmChatAuthorAttribution;
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

    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.center.PersonalNotificationWriter personalNotifications;

    /** Атрибуция авторов исходящих через crm.employee маппинг (13.5); поле — контракт конструктора стабилен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CrmChatAuthorAttribution authorAttribution;

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
        // Legacy/direct imports have no trusted observation envelope and create no personal effect.
        return processPlatformMessage(request, null);
    }

    @Transactional
    public MessageDto processPlatformMessage(PlatformMessageRequest request,
            kit.penny.clientbus.server.notification.contract.NotificationSourceContext source) {

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

        // 13.5 (живая приёмка DIKIDI): автор исходящего на стороне салона различается
        // платформенным id — сотрудник берётся из существующего маппинга мастеров
        // (crm.employee.externalid → employeeid); без мастера/маппинга — NULL.
        UUID attributedEmployeeId = outbound && authorAttribution != null
                ? authorAttribution.employeeId(channelAccount, request.authorExternalId())
                : null;

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
                        request.clientDisplayName(),
                        null
                );

        ConversationEntity conversation =
                conversationService.createConversationInternal(
                        channelAccount,
                        clientAccount,
                        request.dialogExternalId()
                );

        MessageCreationResult result =
                messageService.createPlatformMessage(
                        new CreatePlatformMessageRequest(
                                conversation.getId(),
                                request.type(),
                                request.externalId(),
                                request.content(),
                                request.metadata(),
                                request.sentAt(),
                                outbound,
                                attributedEmployeeId
                        ), request.origin()
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
        } else if (outbound && Boolean.TRUE.equals(request.recipientRead())) {

            // 13.6 (отчёт о доставке): повторное чтение платформы показало, что
            // собеседник прочитал исходящее сообщение (DIKIDI out && read>0 — признак
            // подтверждён кодом веб-клиента). Продвижение толерантное: только
            // SENT/DELIVERED -> READ, FAILED/PENDING не затрагивается.
            messageService.markReadFromPlatformReceipt(
                    result.message().id()
            );
        }

        if (!outbound && source != null && personalNotifications != null) {
            if (source.origin() != request.origin()) throw new IllegalArgumentException("Source origin mismatch");
            messageRepository.flush();
            personalNotifications.incomingMessage(conversation.getId(), channelAccount.getId(), request.externalId(), source);
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
                        request.clientDisplayName(),
                        request.avatarUrl()
                );

        ConversationEntity conversation =
                conversationService.findEntityForDialog(
                        channelAccount.getId(),
                        clientAccount.getId(),
                        request.dialogExternalId()
                );

        boolean created = false;

        if (conversation == null) {

            conversation =
                    conversationService.createConversationInternal(
                            channelAccount,
                            clientAccount,
                            request.dialogExternalId()
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
                messageService.markDeliveryFailed(result.messageId(), result.command().sendAttemptId());
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
                messageService.markDeliveryFailed(result.messageId(), result.command().sendAttemptId());
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    /*
     * 13.2b: forward acceptance locks the target conversation FOR UPDATE and re-runs the
     * forward-target authorization under that lock (OutboundMessageTransactionService.
     * prepareForward), then publishes the queued send exactly like sendMessage does.
     * Previously the forwarded message was persisted as OUTBOUND but never published,
     * so it stayed in the queue forever.
     */
    @Override
    public MessageDto forwardMessage(
            ForwardMessageRequest request
    ) {

        UUID targetConversationId;

        if (request.targetConversationId() != null) {

            targetConversationId =
                    request.targetConversationId();

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

            targetConversationId =
                    conversationService.findOrCreateForForward(
                            channelAccount,
                            clientAccount
                    ).getId();
        }

        OutboundMessageTransactionService.OutboundMessageTransactionResult result =
                outboundMessageTransactionService.prepareForward(
                        request.messageId(),
                        targetConversationId
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
                        result.messageId(), result.command().sendAttemptId());
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
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
                (event.sendAttemptId() == null
                        ? messageRepository.findByConversationChannelAccountIdAndExternalId(
                                event.channelAccountId(), event.externalId())
                        : messageRepository.findBySendAttemptIdAndConversationChannelAccountIdAndExternalId(
                                event.sendAttemptId(), event.channelAccountId(), event.externalId()))
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
                            message.getId(), event.sendAttemptId()
                    );

            case READ ->
                    messageService.markRead(
                            message.getId(), event.sendAttemptId()
                    );

            case FAILED ->
                    messageService.markDeliveryFailed(
                            message.getId(), event.sendAttemptId()
                    );
        };
    }
}
