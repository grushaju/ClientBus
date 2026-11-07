package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.CrmFinancialJson;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrmFinanceServiceTest {
    private static final CrmFinanceQuery QUERY=new CrmFinanceQuery("30",LocalDate.of(2026,10,27),new CrmClientReference("PROJECT","20"));
    private static CrmFinanceSnapshot snapshot(String cost,String paid,String debt,String currency) {
        return new CrmFinanceSnapshot("100",QUERY,"40",currency,decimal(cost),decimal(paid),decimal(debt),null,null,
                List.of(),List.of(),Instant.now(),CrmReadResult.Completeness.UNKNOWN);
    }
    private static BigDecimal decimal(String value) { return value==null ? null : new BigDecimal(value); }
    @Test void paymentStatesRequireKnownConsistentAmountsAndCurrency() {
        assertEquals("PAID",CrmFinanceService.paymentState(snapshot("3500","3500","0","RUB")));
        assertEquals("PARTIALLY_PAID",CrmFinanceService.paymentState(snapshot("3500","1500","2000","RUB")));
        assertEquals("UNPAID",CrmFinanceService.paymentState(snapshot("3500","0","3500","RUB")));
        for (var value:List.of(snapshot("3500","3500",null,"RUB"),snapshot("3500","3500","0",null),
                snapshot("3500","3500","100","RUB"))) assertEquals("UNKNOWN",CrmFinanceService.paymentState(value));
    }
    @Test void scopeAndMoneyAreValidatedBeforePublishing() {
        assertDoesNotThrow(() -> CrmFinanceService.validate(snapshot("3500","3500","0","RUB"),"100",QUERY));
        assertThrows(CrmFailure.class,() -> CrmFinanceService.validate(snapshot("3500","3500","0","RUB"),"101",QUERY));
        for (String amount:List.of("-1","1E+100","0.12345"))
            assertThrows(CrmFailure.class,() -> CrmFinanceService.validate(snapshot(amount,"0","0","RUB"),"100",QUERY));
    }
    @Test void nestedAmountsAndDuplicatePaymentsAreRejected() {
        var badComponent=new CrmFinanceSnapshot.Component("cash","80",new BigDecimal("-1"));
        var payment=new CrmFinanceSnapshot.Payment("70","27.10.2026",List.of(badComponent));
        var value=new CrmFinanceSnapshot("100",QUERY,"40","RUB",null,null,null,null,null,List.of(),List.of(payment),Instant.now(),CrmReadResult.Completeness.UNKNOWN);
        assertThrows(CrmFailure.class,() -> CrmFinanceService.validate(value,"100",QUERY));
        var empty=new CrmFinanceSnapshot.Payment("70","27.10.2026",List.of());
        var duplicate=new CrmFinanceSnapshot("100",QUERY,"40","RUB",null,null,null,null,null,List.of(),List.of(empty,empty),Instant.now(),CrmReadResult.Completeness.UNKNOWN);
        assertThrows(CrmFailure.class,() -> CrmFinanceService.validate(duplicate,"100",QUERY));
    }
    @Test void financialJsonPreservesDecimalAmountsAcrossEveryConversion() {
        var exact=new BigDecimal("123456789012345.1234");
        var value=new CrmFinanceSnapshot("100",QUERY,"40","RUB",exact,exact,BigDecimal.ZERO,new BigDecimal("30.125"),new BigDecimal("0.0001"),
                List.of(new CrmFinanceSnapshot.Line("50","10","60",exact,new BigDecimal("30.125"),new BigDecimal("1500.0001"),exact)),
                List.of(new CrmFinanceSnapshot.Payment("70","27.10.2026",List.of(new CrmFinanceSnapshot.Component("cash","80",exact)))),
                Instant.now(),CrmReadResult.Completeness.UNKNOWN);
        CrmFinanceService.validate(value,"100",QUERY);
        Map<String,Object> restored=CrmFinancialJson.read(CrmFinancialJson.write(CrmFinancialJson.object(value)));
        assertEquals(exact,assertInstanceOf(BigDecimal.class,restored.get("cost")));
        var line=(Map<?,?>)((List<?>)restored.get("lines")).getFirst();
        assertEquals(new BigDecimal("1500.0001"),assertInstanceOf(BigDecimal.class,line.get("discountAmount")));
        var payment=(Map<?,?>)((List<?>)restored.get("payments")).getFirst();
        var component=(Map<?,?>)((List<?>)payment.get("components")).getFirst();
        assertEquals(exact,assertInstanceOf(BigDecimal.class,component.get("amount")));
        assertEquals(new BigDecimal("0.0001"),assertInstanceOf(BigDecimal.class,restored.get("headBonus")));
    }
}
