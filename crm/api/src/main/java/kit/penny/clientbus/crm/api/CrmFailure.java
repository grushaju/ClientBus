package kit.penny.clientbus.crm.api;

/** Safe stable codes only; provider response text must never become an exception message/cause. */
public final class CrmFailure extends RuntimeException {
    public enum Code {
        AUTH_REQUIRED, ACCESS_DENIED, CAPABILITY_UNVERIFIED, UNSUPPORTED, CONFIGURATION_REQUIRED,
        RATE_LIMITED, CONNECTION_BUSY, TEMPORARY_FAILURE, INVALID_RESPONSE, CONTEXT_MISMATCH
    }
    private final Code code;
    public CrmFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
