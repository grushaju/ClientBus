package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import java.util.*;

/** Связь строки наблюдения «учётка ↔ сотрудник каталога» (13.6): linkedId=null — снять связь. */
public record LinkCrmStaffRequest(UUID linkedId, @NotNull @Min(0) Long expectedVersion) {}
