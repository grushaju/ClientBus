package kit.penny.clientbus.server.service;

import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformOutboundAttachment;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OutboundMessageTransactionService {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final MessageAttachmentService messageAttachmentService;
    private final CurrentUserService currentUserService;

    public OutboundMessageTransactionService(
            ConversationService conversationService,
            MessageService messageService,
            MessageAttachmentService messageAttachmentService,
            CurrentUserService currentUserService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.messageAttachmentService = messageAttachmentService;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public OutboundMessageTransactionResult prepare(
            OutboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "OutboundMessageRequest must not be null"
            );
        }

        List<AttachmentContent> normalizedAttachments =
                normalizeAttachments(attachments);

        MessageDto message =
                messageService.createOutboundMessage(
                        new CreateOutboundMessageRequest(
                                request.conversationId(),
                                request.type(),
                                request.content(),
                                request.metadata(),
                                request.replyToMessageId()
                        )
                );

        try {
            /*
             * RECEIVED -> PROCESSING
             */
            message =
                    messageService.startProcessing(
                            message.id()
                    );

            ConversationEntity conversation =
                    conversationService.findEntityForProcessing(
                            request.conversationId()
                    );

            ChannelAccountEntity channelAccount =
                    conversation.getChannelAccount();

            if (channelAccount == null) {
                throw new IllegalStateException(
                        "Conversation has no ChannelAccount: "
                                + conversation.getId()
                );
            }

            ChannelType channelType =
                    channelAccount.getChannel()
                            .getType();

            if (channelType == null) {
                throw new IllegalStateException(
                        "ChannelAccount has no ChannelType: "
                                + channelAccount.getId()
                );
            }

            MessageEntity messageEntity =
                    messageService.getMessageEntityForProcessing(
                            message.id()
                    );

            for (AttachmentContent attachment :
                    normalizedAttachments) {

                messageAttachmentService.createAttachment(
                        messageEntity,
                        attachment
                );
            }

            List<MessageAttachmentEntity> messageAttachments =
                    messageAttachmentService
                            .getAttachmentsForProcessing(
                                    message.id()
                            );

            List<PlatformOutboundAttachment> outboundAttachments =
                    messageAttachments.stream()
                            .map(attachment ->
                                    new PlatformOutboundAttachment(
                                            attachment.getType(),
                                            attachment.getStorageKey(),
                                            attachment.getFileName(),
                                            attachment.getContentType(),
                                            attachment.getSize()
                                    )
                            )
                            .toList();

            OutboundMessageKafkaCommand command =
                    new OutboundMessageKafkaCommand(
                            message.id(),
                            channelAccount.getId(),
                            conversation.getClientAccount()
                                    .getExternalId(),
                            message.type(),
                            message.content(),
                            outboundAttachments
                    );

            /*
             * PROCESSING -> QUEUED
             *
             * Kafka publication выполняется вызывающим
             * кодом после возврата из этого метода.
             */
            message =
                    messageService.markQueued(
                            message.id()
                    );

            return new OutboundMessageTransactionResult(
                    message,
                    channelType,
                    command
            );

        } catch (RuntimeException e) {
            try {
                messageService.markProcessingFailed(
                        message.id()
                );
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    @Transactional
    public OutboundMessageTransactionResult prepareRetry(
            UUID messageId
    ) {
        if (messageId == null) {
            throw new IllegalArgumentException(
                    "MessageId must not be null"
            );
        }

        /*
         * Technical load without normal message ACL.
         *
         * Retry has its own authorization rules:
         *  - SUPER_ADMIN: allowed for workspaces in own organization
         *  - EMPLOYEE: only assigned conversation
         */
        MessageEntity messageEntity =
                messageService.getMessageEntityForProcessing(
                        messageId
                );

        if (messageEntity.getDirection()
                != MessageDirection.OUTBOUND) {

            throw new IllegalStateException(
                    "Message must be OUTBOUND before retry: "
                            + messageId
            );
        }

        /*
         * Capture all values that are needed after retryDelivery().
         *
         * retryDelivery() performs a bulk update with
         * clearAutomatically = true, therefore all lazy
         * associations must not be accessed afterwards.
         */
        UUID conversationId =
                messageEntity.getConversation().getId();

        var messageType =
                messageEntity.getType();

        String messageContent =
                messageEntity.getContent();

        try {
            ConversationEntity conversation =
                    conversationService.findEntityForProcessing(
                            conversationId
                    );

            /*
             * Workspace access is the first authorization boundary.
             *
             * This guarantees that an employee without access
             * to the workspace receives AccessDeniedException
             * before the employee-assignment check.
             */
            currentUserService.requireWorkspaceAccess(
                    conversation.getWorkspace().getId()
            );

            /*
             * EMPLOYEE may retry only a conversation assigned
             * to the current employee.
             */
            if (currentUserService.isEmployee()) {
                EmployeeEntity currentEmployee =
                        currentUserService.getCurrentEmployee();

                EmployeeEntity assignedEmployee =
                        conversation.getAssignedEmployee();

                if (assignedEmployee == null
                        || !assignedEmployee.getId().equals(
                        currentEmployee.getId()
                )) {

                    throw new IllegalStateException(
                            "Current employee is not assigned to the conversation: "
                                    + conversationId
                    );
                }
            }

            ChannelAccountEntity channelAccount =
                    conversation.getChannelAccount();

            if (channelAccount == null) {
                throw new IllegalStateException(
                        "Conversation has no ChannelAccount: "
                                + conversation.getId()
                );
            }

            /*
             * Capture values before retryDelivery().
             *
             * retryDelivery() clears the persistence context.
             */
            UUID channelAccountId =
                    channelAccount.getId();

            ChannelType channelType =
                    channelAccount.getChannel()
                            .getType();

            if (channelType == null) {
                throw new IllegalStateException(
                        "ChannelAccount has no ChannelType: "
                                + channelAccount.getId()
                );
            }

            String clientAccountExternalId =
                    conversation.getClientAccount()
                            .getExternalId();

            /*
             * PROCESSED + FAILED
             *          ->
             * PROCESSING + PENDING
             */
            MessageDto message =
                    messageService.retryDelivery(
                            messageId
                    );

            List<MessageAttachmentEntity> messageAttachments =
                    messageAttachmentService
                            .getAttachmentsForProcessing(
                                    messageId
                            );

            List<PlatformOutboundAttachment> outboundAttachments =
                    messageAttachments.stream()
                            .map(attachment ->
                                    new PlatformOutboundAttachment(
                                            attachment.getType(),
                                            attachment.getStorageKey(),
                                            attachment.getFileName(),
                                            attachment.getContentType(),
                                            attachment.getSize()
                                    )
                            )
                            .toList();

            OutboundMessageKafkaCommand command =
                    new OutboundMessageKafkaCommand(
                            messageId,
                            channelAccountId,
                            clientAccountExternalId,
                            messageType,
                            messageContent,
                            outboundAttachments
                    );

            /*
             * PROCESSING -> QUEUED
             */
            message =
                    messageService.markQueued(
                            messageId
                    );

            return new OutboundMessageTransactionResult(
                    message,
                    channelType,
                    command
            );

        } catch (RuntimeException e) {
            try {
                messageService.markProcessingFailed(
                        messageId
                );
            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    private List<AttachmentContent> normalizeAttachments(
            List<AttachmentContent> attachments
    ) {
        if (attachments == null) {
            return List.of();
        }

        return List.copyOf(attachments);
    }

    public record OutboundMessageTransactionResult(
            MessageDto message,
            ChannelType channelType,
            OutboundMessageKafkaCommand command
    ) {
        public OutboundMessageTransactionResult {
            if (message == null) {
                throw new IllegalArgumentException(
                        "Message must not be null"
                );
            }

            if (channelType == null) {
                throw new IllegalArgumentException(
                        "ChannelType must not be null"
                );
            }

            if (command == null) {
                throw new IllegalArgumentException(
                        "Command must not be null"
                );
            }
        }

        public UUID messageId() {
            return message.id();
        }
    }
}