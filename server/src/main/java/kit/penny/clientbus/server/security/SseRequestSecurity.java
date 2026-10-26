package kit.penny.clientbus.server.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

/** A servlet attribute belongs to one admitted request; HTTP headers cannot create this proof. */
public final class SseRequestSecurity {
    public static final String ADMITTED = SseRequestSecurity.class.getName() + ".admitted";
    private SseRequestSecurity() {}

    public static boolean isContinuation(HttpServletRequest request) {
        return (request.getDispatcherType() == DispatcherType.ASYNC || request.getDispatcherType() == DispatcherType.ERROR)
                && Boolean.TRUE.equals(request.getAttribute(ADMITTED));
    }
}
