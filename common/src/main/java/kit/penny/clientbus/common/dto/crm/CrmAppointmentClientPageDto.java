package kit.penny.clientbus.common.dto.crm;
import java.util.List;

public record CrmAppointmentClientPageDto(List<CrmAppointmentClientDto> items, String nextCursor, boolean hasMore,
        CrmSyncStateDto dataState, String revision) {}
