package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import java.util.UUID;

/** Выбор учётки-оператора для записи доступа (13.6); employeeId=null снимает связь. */
public record LinkCrmStaffAccessRequest(UUID employeeId, @NotNull @Min(0) Long expectedVersion) {}
