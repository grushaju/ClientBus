package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.Instant;
import java.util.*;

public record CrmMasterPageDto(List<CrmMasterDto> items, String nextCursor, boolean hasMore, CrmSyncStateDto dataState, long revision) {
    public CrmMasterPageDto(List<CrmMasterDto> items, String nextCursor, boolean hasMore, CrmSyncStateDto dataState) { this(items, nextCursor, hasMore, dataState, 0); }
}
