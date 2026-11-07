package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

class DikidiFinanceContractTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,27);
    private static final CrmFinanceQuery QUERY=new CrmFinanceQuery("30",DAY,new CrmClientReference("PROJECT","20"));
    private static final String JOURNAL="""
            {"company":{"accesses":{"100":{"actor":{"id":"1"}}}},"masters":{
              "info":{"ids":["10"],"data":{"10":{"id":"10","item_type":"master","is_master":"1","title":"Master"}}},
              "records":{"data":{"10":{"2026-10-27":{"30":{"id":"30","item_id":"10","begin":"10:00:00","end":"11:00:00",
                "info":{"client_id":"20","client_is_system":"0","is_group_session":"0","is_time_reservation":"0",
                "payment":{"document_id":"40","cost":"3500.00","paid":"3500.00","debt":"0"}}}}}}}}}
            """;
    private static final String LINE="""
            <tr><td><input name='services_id[]' value='50'><input name='services_master_id[]' value='10'>
            <input name='services_service_id[]' value='60'><input name='services_price[]' value='5000.00'>
            <input name='services_discount[]' value='30'><input name='services_discount_cost[]' value='1500.00'>
            <input name='services_total[]' value='3500.00'></td></tr>
            """;
    private static final String SALE="<form><input name='sale_id' value='40'><input name='table' value='clients'>"
            +"<input name='table_id' value='20'><input name='head_discount' value='0'><input name='head_bonus' value='0'>"
            +"<input name='total-endpay' value='0'><table>"+LINE+"</table></form>";
    private static final String PAYMENTS="<a href='/ru/owner/ajax/sales_payments/form/?company=100&amp;sale=40&amp;id=70'>Payment</a>";
    private static final String COMPONENT="<div><input name='method[]' value='cash'><input name='price[]' value='3500.00'>"
            +"<select name='method_id[]'><option value='80' selected>Cash register</option></select></div>";
    private static final String PAYMENT="<form><input name='sale_id' value='40'><input name='payment_id' value='70'>"
            +"<input name='date' value='27.10.2026'>"+COMPONENT
            +"<div><input name='method[]' value='card'><input name='price[]' value=''><input name='method_id[]' value=''></div></form>";
    private static final String CURRENCY="{\"data\":{\"currency\":{\"iso\":\"RUB\"}}}";
    private static String html(String body) { return JsonMapper.builder().build().writeValueAsString(Map.of("id","ui-dialog","html",body)); }
    private static final class Fake implements DikidiTransport {
        final Deque<Response> queue=new ArrayDeque<>(); final List<java.net.URI> uris=new ArrayList<>();
        Fake(String journal,String sale,String payments,String payment) {
            for (String body:List.of("<input type='hidden' name='telegram_csrf' value='first'>",
                    "{\"html\":\"<input type='hidden' name='csrf' value='next'>\"}","{\"callback\":[\"sw.auth.complete();\"]}",
                    "journal",journal,html(sale),html(payments),html(payment),CURRENCY)) queue.add(new Response(200,body));
        }
        public Response exchange(String method,java.net.URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout) {
            uris.add(uri); assertFalse(uri.getPath().matches(".*(?:save|remove|cancel|reserve|/pay/).*"));
            var response=queue.poll(); assertNotNull(response,"Unexpected request"); return response;
        }
    }
    private static CrmFinanceSnapshot read(Fake fake) {
        try (var provider=new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(15),20000),() -> fake);
             var access=new CrmAccess(UUID.randomUUID(),1,"test-login","test-password".toCharArray())) {
            return provider.readFinance(access,"100",QUERY);
        }
    }
    @Test void freshJournalLinksSaleDiscountAndActualPaymentWithoutExecutingForms() {
        var fake=new Fake(JOURNAL,SALE,PAYMENTS,PAYMENT); var result=read(fake);
        assertEquals("40",result.externalSaleId()); assertEquals("RUB",result.currency());
        assertEquals(0,new BigDecimal("3500").compareTo(result.cost()));
        assertEquals(0,new BigDecimal("1500").compareTo(result.lines().getFirst().discountAmount()));
        assertEquals(0,new BigDecimal("30").compareTo(result.lines().getFirst().discountPercent()));
        assertEquals(1,result.payments().size()); assertEquals(1,result.payments().getFirst().components().size());
        assertEquals("cash",result.payments().getFirst().components().getFirst().method());
        assertEquals("80",result.payments().getFirst().components().getFirst().externalMethodId());
        assertEquals(CrmReadResult.Completeness.UNKNOWN,result.completeness());
        assertEquals(9,fake.uris.size()); assertTrue(fake.queue.isEmpty());
    }
    @Test void newSaleFormOrOtherClientCannotBecomeFinancialEvidence() {
        for (String sale:List.of(SALE.replace("name='sale_id' value='40'","name='sale_id' value=''"),
                SALE.replace("name='table_id' value='20'","name='table_id' value='99'")))
            assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> read(new Fake(JOURNAL,sale,PAYMENTS,PAYMENT))).code());
    }
    @Test void anotherPaymentOrSaleCannotBeApplied() {
        for (String payment:List.of(PAYMENT.replace("name='sale_id' value='40'","name='sale_id' value='41'"),
                PAYMENT.replace("name='payment_id' value='70'","name='payment_id' value='71'")))
            assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> read(new Fake(JOURNAL,SALE,PAYMENTS,payment))).code());
    }
    @Test void conflictingJournalIdentityFailsBeforeFollowingDocument() {
        var fake=new Fake(JOURNAL.replace("\"client_id\":\"20\"","\"client_id\":\"99\""),SALE,PAYMENTS,PAYMENT);
        assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> read(fake)).code()); assertEquals(5,fake.uris.size());
    }
    @Test void paymentLinksCannotChangeContextOrDestination() {
        for (String links:List.of(PAYMENTS.replace("sale=40","sale=41"),PAYMENTS.replace("company=100","company=101"),
                PAYMENTS.replace("href='/ru/","href='https://evil.example/ru/")))
            assertThrows(CrmFailure.class,() -> read(new Fake(JOURNAL,SALE,links,PAYMENT)));
    }
    @Test void newPaymentNavigationIsIgnoredAndNeverReadAsAnExistingPayment() {
        var fake=new Fake(JOURNAL,SALE,PAYMENTS+"<a href='/ru/owner/ajax/sales_payments/form/?company=100&amp;sale=40'>Add</a>"
                +"<a href='javascript:void(0)'>Menu</a>",PAYMENT);
        assertEquals(1,read(fake).payments().size()); assertEquals(9,fake.uris.size());
    }
    @Test void paidVisitWithoutReadablePaymentListIsNotAnEmptyPaymentResult() {
        assertEquals(CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,() -> read(new Fake(JOURNAL,SALE,"<div>Unavailable</div>",PAYMENT))).code());
    }
    @Test void zeroOrUnknownPaidAmountDoesNotValidateAnUnavailablePaymentList() {
        for (String journal:List.of(JOURNAL.replace("\"paid\":\"3500.00\"","\"paid\":\"0\""),
                JOURNAL.replace("\"paid\":\"3500.00\",",""))) {
            for (String body:List.of("<div>Unavailable</div>","", "<input name='password'>",
                    "<a href='/ru/owner/ajax/sales_payments/form/?company=100&amp;sale=40'>Add</a>",
                    "<!-- <a href='/ru/owner/ajax/sales_payments/form/?company=100&amp;sale=40&amp;id=70'> -->")) {
                var fake=new Fake(journal,SALE,body,PAYMENT);
                assertEquals(CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,() -> read(fake)).code());
                assertEquals(7,fake.uris.size());
            }
        }
    }
    @Test void existingPaymentsRemainReadableWhenJournalPaidTotalIsZeroOrUnknown() {
        for (String journal:List.of(JOURNAL.replace("\"paid\":\"3500.00\"","\"paid\":\"0\""),
                JOURNAL.replace("\"paid\":\"3500.00\",",""))) {
            var result=read(new Fake(journal,SALE,PAYMENTS,PAYMENT));
            assertEquals(1,result.payments().size());
            assertEquals("70",result.payments().getFirst().externalPaymentId());
        }
    }
    @Test void rowsRetainTheirOwnDiscountsAndDoNotZipUnrelatedInputs() {
        String second=LINE.replace("value='50'","value='51'").replace("value='30'","value='10'");
        var lines=DikidiFinanceHtml.parse("<table>"+LINE+second+"</table>").lines();
        assertEquals(List.of("50","51"),lines.stream().map(CrmFinanceSnapshot.Line::externalLineId).toList());
        assertEquals(new BigDecimal("1E+1"),lines.getLast().discountPercent());
        String broken="<table>"+LINE.replace("name='services_price[]'","name='missing'")+second+"</table>";
        assertThrows(CrmFailure.class,() -> DikidiFinanceHtml.parse(broken).lines());
    }
    @Test void malformedAndNegativeMoneyIsNotConvertedToZero() {
        for (String value:List.of("-1","NaN","1e4","1,00","1.12345"))
            assertThrows(CrmFailure.class,() -> DikidiFinanceHtml.amount(value));
        assertNull(DikidiFinanceHtml.amount("")); assertEquals(BigDecimal.ZERO,DikidiFinanceHtml.amount("0.0000"));
    }
    @Test void missingDocumentIsUnverifiedAndCannotEraseAnEarlierSale() {
        for (String journal:List.of(JOURNAL.replace("\"document_id\":\"40\"","\"document_id\":\"0\""),
                JOURNAL.replace("\"document_id\":\"40\",",""))) {
            var fake=new Fake(journal,SALE,PAYMENTS,PAYMENT);
            assertEquals(CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,() -> read(fake)).code());
            assertEquals(5,fake.uris.size());
        }
    }
}
