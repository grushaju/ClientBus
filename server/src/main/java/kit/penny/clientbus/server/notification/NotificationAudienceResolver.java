package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.UUID;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.*;

/** Initial recipient decision. Writer must hold the domain lock and persist even an empty result. */
@Service
public class NotificationAudienceResolver {
    private final AccessPolicy policy;
    public NotificationAudienceResolver(AccessPolicy policy) { this.policy = policy; }
    public Set<UUID> assignment(UUID conversationId, UUID actorEmployeeId) {
        return policy.audience(ASSIGNED_CONVERSATION, conversationId).stream()
                .filter(id -> !id.equals(actorEmployeeId)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public Set<UUID> incoming(UUID conversationId) { return policy.audience(ASSIGNED_CONVERSATION, conversationId); }
    public Set<UUID> outboundFailure(UUID conversationId) {
        var assigned = incoming(conversationId);
        return assigned.isEmpty() ? policy.audience(CONVERSATION_SUPERVISORS, conversationId) : assigned;
    }
    public Set<UUID> channelIncident(UUID channelAccountId) { return policy.audience(CHANNEL_ACCOUNT_MANAGEMENT, channelAccountId); }
    public Set<UUID> operation(UUID operationId) { return policy.audience(HISTORY_OPERATION, operationId); }
}
