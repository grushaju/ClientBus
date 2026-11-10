package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.CrmSourceHealth;
import kit.penny.clientbus.server.persistence.entity.crm.CrmConnectionEntity;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CrmSourceHealthTest {
    private CrmConnectionEntity connection(){
        var row=new CrmConnectionEntity();row.setId(UUID.randomUUID());row.setStatus(CrmConnectionStatus.CONNECTED);row.setHealthGeneration(1);return row;
    }
    @Test void originalFailureCannotOverrideACompletedNewerVerification(){
        var row=connection();var source=CrmHealthCheck.source(new CrmSourceHealth(row.getId(),row.getCredentialVersion(),1));
        var verified=CrmHealthCheck.begin(row);assertTrue(verified.apply(row,CrmConnectionStatus.CONNECTED,null));
        var checked=row.getLastCheckedAt();
        assertFalse(source.apply(row,CrmConnectionStatus.REAUTH_REQUIRED,"CRM_REAUTH_REQUIRED"));
        assertEquals(CrmConnectionStatus.CONNECTED,row.getStatus());assertEquals(checked,row.getLastCheckedAt());assertEquals(2,row.getHealthAppliedGeneration());
    }
    @Test void aLaterUnfinishedCheckDoesNotHideARealFailureButItsOwnSuccessSupersedesIt(){
        var row=connection();var source=CrmHealthCheck.source(new CrmSourceHealth(row.getId(),row.getCredentialVersion(),1));
        var pending=CrmHealthCheck.begin(row);
        assertTrue(source.apply(row,CrmConnectionStatus.REAUTH_REQUIRED,"CRM_REAUTH_REQUIRED"));
        assertEquals(CrmConnectionStatus.REAUTH_REQUIRED,row.getStatus());assertEquals(1,row.getHealthAppliedGeneration());
        assertTrue(pending.apply(row,CrmConnectionStatus.CONNECTED,null));
        assertEquals(CrmConnectionStatus.CONNECTED,row.getStatus());assertFalse(source.apply(row,CrmConnectionStatus.REAUTH_REQUIRED,"CRM_REAUTH_REQUIRED"));
    }
}
