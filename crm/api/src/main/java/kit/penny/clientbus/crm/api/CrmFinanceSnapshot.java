package kit.penny.clientbus.crm.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Fresh financial evidence; null amounts are unknown, never implicit zero. */
public record CrmFinanceSnapshot(String externalProjectId, CrmFinanceQuery scope, String externalSaleId,
        String currency, BigDecimal cost, BigDecimal paid, BigDecimal debt, BigDecimal headDiscount,
        BigDecimal headBonus, List<Line> lines, List<Payment> payments, Instant observedAt,
        CrmReadResult.Completeness completeness) {
    public CrmFinanceSnapshot { lines=List.copyOf(lines); payments=List.copyOf(payments); }
    public record Line(String externalLineId, String externalMasterId, String externalServiceId,
            BigDecimal price, BigDecimal discountPercent, BigDecimal discountAmount, BigDecimal total) {}
    public record Payment(String externalPaymentId, String localDate, List<Component> components) {
        public Payment { components=List.copyOf(components); }
    }
    public record Component(String method, String externalMethodId, BigDecimal amount) {}
}
