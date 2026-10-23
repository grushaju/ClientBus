package kit.penny.clientbus.server.security.policy;

import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** HTTP adapter only; callback/startup code uses explicit subjects and never this adapter. */
@Service
public class HttpAccessPolicy {
    private final CurrentUserService currentUser;
    private final AccessPolicy policy;
    public HttpAccessPolicy(CurrentUserService currentUser, AccessPolicy policy) {
        this.currentUser = currentUser; this.policy = policy;
    }
    public void require(AccessPolicy.Resource resource, UUID id) {
        policy.require(AccessSubject.recipient(currentUser.getCurrentEmployeeId()), resource, id);
    }

    public AccessSubject sessionSubject() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof JwtAuthenticationFilter.VerifiedSession(
                Instant expiresAt))  || expiresAt == null) {
            throw new AccessDeniedException("Verified session required");
        }
        return AccessSubject.session(currentUser.getCurrentEmployeeId(), expiresAt);
    }
}
