package kit.penny.clientbus.common.dto.crm;
import java.util.*;

public record CrmAppointmentPageDto(List<CrmAppointmentDto> items, String nextCursor, boolean hasMore,
        List<CrmAppointmentCoverageDto> coverage, String revision) {}
