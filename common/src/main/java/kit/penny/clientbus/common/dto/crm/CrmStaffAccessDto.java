package kit.penny.clientbus.common.dto.crm;
import java.time.Instant;
import java.util.*;

/**
 * Наблюдаемая запись доступа DIKIDI (13.6): externalId — id записи правок, не user id.
 * employeeId — учётка-оператор (crm.employee), подобранная человеком (моста в DIKIDI нет);
 * operatorMappingEmployeeId/DisplayName — сотрудник ClientBus, привязанный к этой учётке,
 * чтобы вкладка «Сотрудники» показывала цепочку запись → оператор → сотрудник целиком.
 */
public record CrmStaffAccessDto(UUID id, UUID projectId, String externalId, String displayName,
        String masterExternalId, String phone, String roleLabel, boolean mainOwner,
        String observationState, Instant lastObservedAt, long version, UUID operatorId,
        String operatorDisplayName, UUID operatorMappingEmployeeId, Instant operatorMappingUpdatedAt) {}
