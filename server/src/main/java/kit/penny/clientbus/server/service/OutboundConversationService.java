package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.CreateOutboundConversationRequest;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.command.ReadClientAccountAvatarCommand;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** External image I/O is outside the short transaction creating the account and conversation. */
@Service
public class OutboundConversationService {
    private static final Logger log = LoggerFactory.getLogger(OutboundConversationService.class);
    private static final int MAX_AVATAR_BYTES = 5 * 1024 * 1024;
    private final ConversationService conversations;
    private final ChannelAvailabilityService availability;
    private final IChannelConnectorRegistry connectors;
    private final IAttachmentStorage storage;
    private final ClientAccountRepository accounts;
    private final TransactionTemplate transaction;

    public OutboundConversationService(ConversationService conversations, ChannelAvailabilityService availability,
            IChannelConnectorRegistry connectors,
            @org.springframework.beans.factory.annotation.Qualifier("attachmentStorage") IAttachmentStorage storage,
            ClientAccountRepository accounts,
            PlatformTransactionManager transactions) {
        this.conversations = conversations;
        this.availability = availability;
        this.connectors = connectors;
        this.storage = storage;
        this.accounts = accounts;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConversationDto create(CreateOutboundConversationRequest request) {
        var target = conversations.prepareOutboundConversation(request);
        availability.requireConnected(request.channelAccountId());
        String ownedKey = null;
        var mayBeReferenced = new AtomicBoolean();
        try {
            try {
                var image = connectors.getConnector(request.channelType()).handle(
                        new ReadClientAccountAvatarCommand(request.channelAccountId(), request.externalId()));
                if (image.isPresent()) {
                    var content = image.get().content();
                    String type = image.get().contentType();
                    if (content.length > 0 && content.length <= MAX_AVATAR_BYTES
                            && java.util.Set.of("image/jpeg", "image/png", "image/webp").contains(type)
                            && !sameImage(target.avatarKey(), content)) {
                        String extension = type.equals("image/png") ? ".png" : type.equals("image/webp") ? ".webp" : ".jpg";
                        ownedKey = storage.store(new ByteArrayInputStream(content), "avatar" + extension,
                                content.length, type).storageKey();
                    }
                }
            } catch (RuntimeException unavailable) {
                // A missing photo never makes the recipient or creation unavailable.
                log.debug("Outbound avatar unavailable; continuing without a new image");
            }
            final String candidate = ownedKey;
            Saved saved;
            try { saved = save(request, target.avatarKey(), candidate, mayBeReferenced); }
            catch (DataIntegrityViolationException concurrentInsert) {
                // PostgreSQL marks a failed insert transaction aborted. Retry only after its rollback.
                saved = save(request, target.avatarKey(), candidate, mayBeReferenced);
            }
            if (saved.usedCandidate()) ownedKey = null;
            return saved.conversation();
        } finally {
            if (ownedKey != null && !mayBeReferenced.get()) {
                try { storage.delete(ownedKey); }
                catch (RuntimeException cleanupFailure) { log.warn("Unused outbound avatar cleanup failed"); }
            }
        }
    }

    private record Saved(ConversationDto conversation, boolean usedCandidate) {}

    private Saved save(CreateOutboundConversationRequest request, String expectedKey, String candidate,
                       AtomicBoolean mayBeReferenced) {
        return Objects.requireNonNull(transaction.execute(status -> {
            // Both access and connected state are rechecked after the external call.
            conversations.prepareOutboundConversation(request);
            availability.requireConnected(request.channelAccountId());
            var result = conversations.createOutboundConversation(request);
            var account = accounts.findById(result.clientAccountId()).orElseThrow();
            boolean use = candidate != null && Objects.equals(expectedKey, account.getAvatarUrl());
            if (use) {
                // Never delete a referenced object after an afterCommit error or an uncertain commit outcome.
                mayBeReferenced.set(true);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        if (completion == STATUS_ROLLED_BACK) mayBeReferenced.set(false);
                    }
                });
                account.setAvatarUrl(candidate);
            }
            return new Saved(result, use);
        }));
    }

    private boolean sameImage(String key, byte[] content) {
        if (key == null || key.isBlank()) return false;
        try (var input = storage.load(key)) {
            return Arrays.equals(content, input.readNBytes(MAX_AVATAR_BYTES + 1));
        } catch (java.io.IOException | RuntimeException unavailable) { return false; }
    }
}
