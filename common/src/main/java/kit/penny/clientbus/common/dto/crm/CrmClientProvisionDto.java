package kit.penny.clientbus.common.dto.crm;

import java.time.Instant;
import java.util.UUID;

public record CrmClientProvisionDto(UUID id, UUID projectId, UUID clientId, String status, UUID crmClientId,
                                    String errorCode, Instant updatedAt, String phone) {
    public CrmClientProvisionDto(UUID id,UUID projectId,UUID clientId,String status,UUID crmClientId,String errorCode,Instant updatedAt) {
        this(id,projectId,clientId,status,crmClientId,errorCode,updatedAt,null);
    }
    @Override public String toString(){return "CrmClientProvisionDto[id="+id+", status="+status+"]";}
}
