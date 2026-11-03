package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.crm.api.CrmFailure;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import kit.penny.clientbus.server.persistence.repository.crm.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CrmStore {
    final EntityManager em;
    final CrmConnectionRepository connections;
    final CrmProjectRepository projects;
    final CrmMasterRepository masters;
    final CrmAccessGuard guard;
    final CrmStorage storage;
    public CrmStore(EntityManager em, CrmConnectionRepository connections, CrmProjectRepository projects,
                    CrmMasterRepository masters, CrmAccessGuard guard, CrmStorage storage) {
        this.em = em; this.connections = connections; this.projects = projects; this.masters = masters;
        this.guard = guard; this.storage = storage;
    }
    CrmConnectionEntity connection(UUID id, UUID organization) {
        return connections.findByIdAndOrganizationId(id, organization).orElseThrow(CrmStore::notFound);
    }
    CrmProjectEntity project(UUID id, UUID organization) {
        var project = projects.findByIdAndOrganizationId(id, organization).orElseThrow(CrmStore::notFound);
        guard.workspace(project.getWorkspace().getId(), false); return project;
    }
    CrmCredentials credentials(CrmConnectionEntity row) {
        if (row.getStatus() == CrmConnectionStatus.DISABLED)
            throw new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Подключение CRM отключено.");
        return new CrmCredentials(row.getId(), row.getOrganization().getId(), row.getProvider(),
                row.getCredentialVersion(), row.getLogin(), row.getSecretKeyId(), row.getSecretCiphertext());
    }
    CrmContext context(CrmProjectEntity row) {
        return new CrmContext(row.getId(), row.getOrganization().getId(), row.getWorkspace().getId(), row.getProvider().name(),
                row.getExternalId(), row.getConnection().getId(), row.getConnection().getCredentialVersion(),
                row.getContextVersion(), row.getZoneRevision());
    }
    CrmProjectEntity requireContext(CrmContext context) {
        // Configuration changes take connection before project. Keep the same order in workers,
        // including their final health update, and refresh values after acquiring the lock.
        var connection = em.find(CrmConnectionEntity.class, context.connectionId());
        if (connection == null) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        em.refresh(connection, LockModeType.PESSIMISTIC_WRITE);
        var row = em.find(CrmProjectEntity.class, context.projectId(), LockModeType.PESSIMISTIC_WRITE);
        if (row != null) em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !row.isEnabled() || row.getConnection().getStatus() == CrmConnectionStatus.DISABLED
                || !context.equals(context(row))) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        return row;
    }
    void lockMappingEmployees(UUID authorId, UUID targetId) {
        // Stable order prevents reciprocal links from deadlocking. NO KEY UPDATE protects
        // both employees against DELETE while allowing the KEY SHARE lock of an audit FK.
        em.createNativeQuery("select employeeid from dbo.employee where employeeid in (:author,:target) order by employeeid for no key update")
                .setParameter("author", authorId).setParameter("target", targetId == null ? authorId : targetId).getResultList();
    }
    void requireCredentials(CrmCredentials credentials) {
        var row = connection(credentials.id, credentials.organizationId);
        em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        if (row.getCredentialVersion() != credentials.version || row.getStatus() == CrmConnectionStatus.DISABLED)
            throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    static CrmConnectionDto dto(CrmConnectionEntity row) {
        String login = row.getLogin();
        return new CrmConnectionDto(row.getId(), row.getProvider(), "••••" + login.substring(Math.max(0, login.length()-4)),
                row.getSecretCiphertext() != null && row.getSecretCiphertext().length > 0, row.getStatus(),
                row.getLastCheckedAt(), row.getLastErrorCode(), row.getVersion(), row.getDisplayName());
    }
    static CrmProjectDto dto(CrmProjectEntity row) {
        List<CrmCapabilityDto> capabilities = new ArrayList<>();
        for (String code : List.of("LIST_PROJECTS", "READ_MASTERS", "READ_SERVICES", "READ_CLIENTS", "READ_APPOINTMENTS",
                "READ_CLIENT_HISTORY", "READ_WORK_INTERVALS", "READ_AVAILABILITY", "READ_FINANCIAL_CONTEXT",
                "CREATE_APPOINTMENT", "MOVE_APPOINTMENT", "CANCEL_APPOINTMENT")) {
            boolean implemented = code.equals("READ_MASTERS") || code.equals("READ_SERVICES");
            String state = !implemented ? "UNVERIFIED" : !row.isEnabled() || row.getConnection().getStatus() == CrmConnectionStatus.DISABLED
                    ? "CONFIGURATION_REQUIRED" : row.getConnection().getStatus() == CrmConnectionStatus.REAUTH_REQUIRED
                    ? "REAUTH_REQUIRED" : "AVAILABLE";
            capabilities.add(new CrmCapabilityDto(code, state, implemented ? null : "NOT_IMPLEMENTED"));
        }
        return new CrmProjectDto(row.getId(), row.getConnection().getId(), row.getWorkspace().getId(), row.getProvider(),
                row.getExternalId(), row.getDisplayName(), row.getCurrency(), row.getZoneId(), row.getZoneSource(),
                row.getZoneRevision(), row.getContextVersion(), row.isEnabled(), row.getConnection().getStatus(), capabilities, row.getVersion());
    }
    static CrmMasterDto dto(CrmMasterEntity row) {
        return new CrmMasterDto(row.getId(), row.getProject().getId(), row.getExternalId(), row.getDisplayName(),
                row.getEmployee() == null ? null : row.getEmployee().getId(), row.getMappingUpdatedAt(),
                row.getMappingUpdatedBy() == null ? null : row.getMappingUpdatedBy().getId(), row.getMappingPreviousId(),
                row.getMappingReason(), row.getLastObservedAt(), row.getObservationState() == null ? "UNKNOWN" : row.getObservationState(), row.getVersion());
    }
    static CrmException notFound() { return new CrmException(404, "NOT_FOUND", "Объект CRM не найден."); }
    static CrmException failure(CrmFailure error) {
        String code = switch (error.code()) {
            case AUTH_REQUIRED -> "CRM_REAUTH_REQUIRED";
            case ACCESS_DENIED -> "CRM_PROVIDER_FORBIDDEN";
            case TEMPORARY_FAILURE -> "CRM_TEMPORARILY_UNAVAILABLE";
            default -> "CRM_" + error.code().name();
        };
        int status = switch (error.code()) {
            case RATE_LIMITED -> 429;
            case TEMPORARY_FAILURE -> 503;
            case INVALID_RESPONSE, CONTEXT_MISMATCH -> 502;
            default -> 409;
        };
        return new CrmException(status, code, switch (error.code()) {
            case AUTH_REQUIRED -> "DIKIDI требует повторной авторизации. Проверьте данные подключения.";
            case ACCESS_DENIED -> "У подключения нет доступа к выбранным данным DIKIDI.";
            case RATE_LIMITED -> "Лимит обращений к CRM. Повторите позже.";
            case TEMPORARY_FAILURE -> "CRM временно недоступна.";
            case INVALID_RESPONSE, CONTEXT_MISMATCH -> "Ответ CRM не прошёл проверку.";
            default -> "Возможность CRM пока недоступна.";
        });
    }
}
