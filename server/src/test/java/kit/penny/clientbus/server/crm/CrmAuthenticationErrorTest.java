package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrmAuthenticationErrorTest {
    @Test void authFailureUsesCredentialGuidanceWithoutAssumingAPreviousSession() {
        var error=CrmStore.failure(new CrmFailure(CrmFailure.Code.AUTH_REQUIRED));
        assertEquals(409,error.status()); assertEquals("CRM_REAUTH_REQUIRED",error.code());
        assertTrue(error.getMessage().contains("номером и паролем"));
        assertFalse(error.getMessage().contains("повторной")); assertNull(error.getCause());
    }
}
