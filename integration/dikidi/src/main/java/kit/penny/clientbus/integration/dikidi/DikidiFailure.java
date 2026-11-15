package kit.penny.clientbus.integration.dikidi;

import java.time.Instant;

/**
 * Техническая ошибка обращения к DIKIDI (13.4). Живёт в integration/dikidi и никогда
 * не уходит в CRM-слой как есть: адаптер crm/dikidi обязан отобразить её в
 * CrmFailure единственным мостом. Коды — только стабильная техника; текст ответа
 * провайдера не попадает ни в сообщение, ни в причину.
 */
public class DikidiFailure extends RuntimeException {
    public enum Code {
        AUTH_REQUIRED, ACCESS_DENIED, RATE_LIMITED, COOLDOWN, CONNECTION_BUSY,
        TEMPORARY_FAILURE, INVALID_RESPONSE
    }
    private final Code code;
    private final Instant retryAt;
    private final boolean notDispatched;
    public DikidiFailure(Code code) { this(code, null, false); }
    public DikidiFailure(Code code, Instant retryAt, boolean notDispatched) {
        super(code.name());
        this.code = code; this.retryAt = retryAt; this.notDispatched = notDispatched;
    }
    public Code code() { return code; }
    public Instant retryAt() { return retryAt; }
    /** Транспорт доказал, что конкретный HTTP-шаг не был передан в сеть. */
    public boolean notDispatched() { return notDispatched; }
}
