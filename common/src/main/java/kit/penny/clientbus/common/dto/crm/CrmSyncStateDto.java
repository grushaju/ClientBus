package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmSyncStateDto(String dataset, String status, Instant lastAttemptAt, Instant lastSuccessAt, String freshness,
                              String completeness, String errorCode, long generation, Map<String, Object> scope,
                              Map<String, Object> successfulCoverage, Map<String, Object> attemptCoverage, Instant retryAt) {
    public CrmSyncStateDto(String dataset,String status,Instant lastAttemptAt,Instant lastSuccessAt,String freshness,String completeness,String errorCode,long generation,Map<String,Object> scope,Map<String,Object> successfulCoverage,Map<String,Object> attemptCoverage) {
        this(dataset,status,lastAttemptAt,lastSuccessAt,freshness,completeness,errorCode,generation,scope,successfulCoverage,attemptCoverage,null);
    }
    public CrmSyncStateDto withRetryAt(Instant value) {
        return new CrmSyncStateDto(dataset,status,lastAttemptAt,lastSuccessAt,freshness,completeness,errorCode,generation,scope,successfulCoverage,attemptCoverage,value);
    }
    public CrmSyncStateDto(String dataset, String status, Instant lastAttemptAt, Instant lastSuccessAt, String freshness,
                           String completeness, String errorCode, long generation) {
        this(dataset, status, lastAttemptAt, lastSuccessAt, freshness, completeness, errorCode, generation, Map.of(), Map.of(), Map.of());
    }
    public CrmSyncStateDto {
        scope = Map.copyOf(scope); successfulCoverage = Map.copyOf(successfulCoverage); attemptCoverage = Map.copyOf(attemptCoverage);
    }
}
