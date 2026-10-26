package kit.penny.clientbus.common.dto.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceStatisticsDto(UUID workspaceId, long employees, long channels, long conversations,
                                     long unread, Instant lastActivity) {}
