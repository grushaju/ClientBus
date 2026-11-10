package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.server.persistence.entity.crm.CrmConnectionEntity;
import java.time.Instant;
import java.util.UUID;

/** A durable connection-wide order, allocated under the connection lock before provider I/O. */
record CrmHealthCheck(UUID connectionId,long credentialVersion,long generation,boolean passive) {
    CrmHealthCheck(UUID connectionId,long credentialVersion,long generation){this(connectionId,credentialVersion,generation,false);}
    static CrmHealthCheck snapshot(CrmConnectionEntity connection){return new CrmHealthCheck(connection.getId(),connection.getCredentialVersion(),connection.getHealthGeneration(),true);}
    static CrmHealthCheck source(kit.penny.clientbus.crm.api.CrmSourceHealth health){return health==null?null:new CrmHealthCheck(health.connectionId(),health.credentialVersion(),health.generation());}
    static CrmHealthCheck begin(CrmConnectionEntity connection) {
        long next=Math.incrementExact(connection.getHealthGeneration());
        connection.setHealthGeneration(next);
        return new CrmHealthCheck(connection.getId(),connection.getCredentialVersion(),next);
    }
    boolean apply(CrmConnectionEntity connection,CrmConnectionStatus status,String errorCode) {
        if(passive){
            if(!connectionId.equals(connection.getId())||credentialVersion!=connection.getCredentialVersion()||connection.getStatus()==CrmConnectionStatus.DISABLED||generation!=connection.getHealthGeneration())return false;
            return begin(connection).apply(connection,status,errorCode);
        }
        if (!connectionId.equals(connection.getId()) || credentialVersion!=connection.getCredentialVersion()
                || connection.getStatus()==CrmConnectionStatus.DISABLED || generation<=connection.getHealthAppliedGeneration()) return false;
        connection.setHealthAppliedGeneration(generation);
        connection.setStatus(status); connection.setLastCheckedAt(Instant.now()); connection.setLastErrorCode(errorCode);
        return true;
    }
}
