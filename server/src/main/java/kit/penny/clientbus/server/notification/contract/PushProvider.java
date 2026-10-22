package kit.penny.clientbus.server.notification.contract;

import kit.penny.clientbus.common.notification.PushProviderType;
import kit.penny.clientbus.common.notification.PushEnvironment;
import kit.penny.clientbus.common.notification.PushPlatform;
import java.time.Duration;
import java.util.UUID;

/** One synchronous provider attempt; worker owns leasing, retries, ACL and persistence. */
public interface PushProvider {
    PushProviderType provider();
    Result send(Request request);

    record Request(UUID notificationId, int schemaVersion, String appId,
                   PushEnvironment environment, PushPlatform platform, String registrationReference,
                   Duration remainingTtl) {
        public Request {
            if (notificationId == null || schemaVersion != 1
                    || appId == null || appId.isBlank() || environment == null || platform == null
                    || registrationReference == null || registrationReference.isBlank()
                    || remainingTtl == null || remainingTtl.isNegative() || remainingTtl.isZero()) {
                throw new IllegalArgumentException("Invalid push attempt");
            }
        }
        @Override
        public String toString() {
            return "PushProvider.Request[notificationId=" + notificationId + ", endpoint=REDACTED]";
        }
    }

    // retryAfter is a delay; the worker caps it at the remaining TTL.
    record Result(Outcome outcome, String providerMessageId, Duration retryAfter) {}
    enum Outcome { ACCEPTED, RETRYABLE, INVALID_ENDPOINT, INVALID_PAYLOAD, REJECTED }
}
