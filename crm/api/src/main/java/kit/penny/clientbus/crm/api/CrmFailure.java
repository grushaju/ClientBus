package kit.penny.clientbus.crm.api;

/** Safe stable codes only; provider response text must never become an exception message/cause. */
public final class CrmFailure extends RuntimeException {
    public enum Code {
        AUTH_REQUIRED, ACCESS_DENIED, CAPABILITY_UNVERIFIED, UNSUPPORTED, CONFIGURATION_REQUIRED,
        RATE_LIMITED, COOLDOWN, CONNECTION_BUSY, TEMPORARY_FAILURE, INVALID_RESPONSE, CONTEXT_MISMATCH, OBSERVATION_CONFLICT
    }
    private final Code code;
    private final java.time.Instant retryAt;
    private final boolean notDispatched;
    private final boolean journalOrigin;
    private final CrmSourceHealth sourceHealth;
    public CrmFailure(Code code) { this(code,null,false); }
    public CrmFailure(Code code,java.time.Instant retryAt,boolean notDispatched) { this(code,retryAt,notDispatched,false,null); }
    private CrmFailure(Code code,java.time.Instant retryAt,boolean notDispatched,boolean journalOrigin,CrmSourceHealth sourceHealth) {
        super(code.name());this.code=code;this.retryAt=retryAt;this.notDispatched=notDispatched;this.journalOrigin=journalOrigin;this.sourceHealth=sourceHealth;
    }
    public CrmFailure withSourceHealth(CrmSourceHealth health){return new CrmFailure(code,retryAt,notDispatched,true,health);}
    public boolean journalOrigin(){return journalOrigin;}
    public CrmSourceHealth sourceHealth(){return sourceHealth;}
    public Code code() { return code; }
    public java.time.Instant retryAt() { return retryAt; }
    public boolean notDispatched() { return notDispatched; }
}
