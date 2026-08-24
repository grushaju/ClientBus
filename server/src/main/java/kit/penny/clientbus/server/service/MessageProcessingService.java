package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;

@Service
public class MessageProcessingService
        implements IMessageProcessingService {

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountService clientAccountService;
    private final ConversationService conversationService;
    private final MessageService messageService;

    public MessageProcessingService(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountService clientAccountService,
            ConversationService conversationService,
            MessageService messageService
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.clientAccountService =
                clientAccountService;

        this.conversationService =
                conversationService;

        this.messageService =
                messageService;
    }

    /**
     * Полный application-level pipeline
     * входящего сообщения.
     *
     * ChannelConnector -> MessageProcessingService
     */
    @Override
    @Transactional
    public MessageDto processInbound(
            InboundMessageRequest request
    ) {

        /*
         * --------------------------------------------------
         * 1. ChannelAccount
         * --------------------------------------------------
         *
         * ChannelConnector сообщает, через какой
         * аккаунт ClientBus пришло сообщение.
         */
        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findById(
                                request.channelAccountId()
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "ChannelAccount not found: "
                                                + request.channelAccountId()
                                )
                        );

        /*
         * ChannelAccount -> Channel -> ChannelType
         */
        ChannelType channelType =
                channelAccount
                        .getChannel()
                        .getType();

        /*
         * --------------------------------------------------
         * 2. ClientAccount
         * --------------------------------------------------
         *
         * Ищем:
         *
         * ChannelType + externalId
         *
         * Если аккаунта нет — создаём ClientAccount.
         *
         * Client НЕ создаём.
         */
        ClientAccountEntity clientAccount =
                clientAccountService.getOrCreateForInbound(
                        channelType,
                        request.clientExternalId(),
                        request.clientUsername(),
                        request.clientPhone(),
                        request.clientDisplayName()
                );

        /*
         * --------------------------------------------------
         * 3. Conversation
         * --------------------------------------------------
         *
         * Conversation определяется:
         *
         * ClientAccount + ChannelAccount
         *
         * Один ClientAccount может иметь несколько
         * Conversation — по одному на каждый ChannelAccount.
         */
        ConversationEntity conversation =
                conversationService.findEntityByAccounts(
                        channelAccount.getId(),
                        clientAccount.getId()
                );

        /*
         * Если Conversation ещё нет —
         * создаём его через существующий application service.
         *
         * Здесь ACL НЕ нужен:
         * это внутренний trusted processing pipeline.
         */
        if (conversation == null) {

            conversation =
                    conversationService.createConversationInternal(
                            channelAccount,
                            clientAccount
                    );
        }

        /*
         * --------------------------------------------------
         * 4. Message
         * --------------------------------------------------
         *
         * ВСЮ lifecycle-логику Message оставляем
         * существующему MessageService:
         *
         * - idempotency
         * - INBOUND
         * - CLIENT sender
         * - RECEIVED
         * - lastMessage
         * - unreadCount
         */
        return messageService.createInboundMessage(
                new CreateInboundMessageRequest(
                        conversation.getId(),
                        request.type(),
                        request.externalId(),
                        request.content(),
                        request.metadata(),
                        request.sentAt()
                )
        );
    }
}