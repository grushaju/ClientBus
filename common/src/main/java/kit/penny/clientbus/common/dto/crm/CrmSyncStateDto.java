package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmSyncStateDto(String dataset, String status, Instant lastAttemptAt, Instant lastSuccessAt, String freshness, String completeness, String errorCode, long generation) {}

