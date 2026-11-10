package kit.penny.clientbus.server.crm;

public final class CrmException extends RuntimeException {
    private final String code;
    private final int status;
    private final java.time.Instant retryAt;
    public CrmException(int status,String code,String message){this(status,code,message,null);}
    public CrmException(int status,String code,String message,java.time.Instant retryAt){super(message);this.status=status;this.code=code;this.retryAt=retryAt;}
    public java.time.Instant retryAt(){return retryAt;}
    public String code() { return code; }
    public int status() { return status; }
    public static CrmException conflict(String code) { return new CrmException(409, code, "Состояние CRM изменилось. Обновите данные."); }
    public static CrmException invalid() { return new CrmException(400, "INVALID_REQUEST", "Проверьте параметры CRM."); }
}
