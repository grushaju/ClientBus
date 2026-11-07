package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DikidiProjectsHtmlTest {
    static final String BUSINESS="<form class='new-elements-list projects' action='/ru/business/ajax/projects/list/' data-method='post'></form>";
    static final String EMPTY="<div class='new-elements-content-block stub-custom'><div class='stub-header'>По вашему запросу ничего не найдено</div><div class='stub-description'><span class='reset-button'>Сбросить</span></div></div>";
    static String row(String id,String name) {
        return "<div class='new-elements-content-block list-item project' data-id='"+id+"'><div class='info'><a class='name' href='/ru/owner/?company="+id+"'>"+name+"</a></div></div>";
    }
    @Test void readsOnlyVerifiedBusinessRowsAndDecodesNames() {
        var projects=DikidiProjectsHtml.projects(row("100","Salon &amp; Spa")+row("200","Second"));
        assertEquals(2,projects.size()); assertEquals("Salon & Spa",projects.getFirst().displayName());
        assertEquals("100",projects.getFirst().externalId()); assertNull(projects.getFirst().currency());
        assertTrue(DikidiProjectsHtml.projects(EMPTY).isEmpty());
    }
    @Test void requiresAnActualAccountListFormNotAnAttributeOrCommentLookalike() {
        assertTrue(DikidiProjectsHtml.businessPage(BUSINESS));
        assertFalse(DikidiProjectsHtml.businessPage("<!--"+BUSINESS+"-->"));
        assertFalse(DikidiProjectsHtml.businessPage(BUSINESS.replace("action=","data-action=")));
        assertFalse(DikidiProjectsHtml.businessPage(BUSINESS.replace("data-method='post'","data-method='get'")));
    }
    @Test void rejectsForeignLinksMismatchedIdsDuplicatesAndUnknownEmptyPages() {
        for (String html:java.util.List.of("", "<div></div>", EMPTY.replace("ничего не найдено","ошибка"),
                row("100","A").replace("?company=100","?company=200"),row("100","A").replace("href='/ru/","href='https://foreign.invalid/ru/"),
                row("100","A")+row("100","B"),row("100",""),row("100","A")+EMPTY,
                row("100","A").replace("class=","data-class="),row("100","A").substring(0,row("100","A").length()-6)))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> DikidiProjectsHtml.projects(html)).code());
    }
}
