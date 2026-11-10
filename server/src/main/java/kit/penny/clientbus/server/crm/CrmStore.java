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
    private final CrmRegistry registry;
    public CrmStore(EntityManager em, CrmConnectionRepository connections, CrmProjectRepository projects,
                    CrmMasterRepository masters, CrmAccessGuard guard, CrmStorage storage, CrmRegistry registry) {
        this.em = em; this.connections = connections; this.projects = projects; this.masters = masters;
        this.guard = guard; this.storage = storage; this.registry=registry;
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
        if (row == null || row.isArchived() || !row.isEnabled() || row.getConnection().getStatus() == CrmConnectionStatus.DISABLED
                || !context.equals(context(row))) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        return row;
    }
    static void requireMutable(CrmProjectEntity project) {
        if (project.isArchived()) throw new CrmException(409, "CRM_PROJECT_ARCHIVED", "Проект CRM в архиве. Доступен только просмотр сохранённых данных.");
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
    CrmProjectDto projectDto(CrmProjectEntity row) {
        List<CrmCapabilityDto> capabilities = new ArrayList<>();
        var commands=registry.require(row.getProvider()).commandCapabilities();
        for (String code : List.of("LIST_PROJECTS", "READ_MASTERS", "READ_SERVICES", "READ_CLIENTS", "READ_APPOINTMENTS",
                "READ_CLIENT_HISTORY", "READ_WORK_INTERVALS", "READ_AVAILABILITY", "READ_FINANCIAL_CONTEXT",
                "CREATE_APPOINTMENT", "MOVE_APPOINTMENT", "CANCEL_APPOINTMENT")) {
            boolean implemented = Set.of("READ_MASTERS","READ_SERVICES","READ_CLIENTS","READ_WORK_INTERVALS","READ_APPOINTMENTS","READ_CLIENT_HISTORY").contains(code)
                    || code.equals("LIST_PROJECTS") && row.getProvider()==CrmType.DIKIDI
                    || code.equals("CREATE_APPOINTMENT") && commands.contains(kit.penny.clientbus.crm.api.CrmCommand.Type.CREATE)
                    || code.equals("MOVE_APPOINTMENT") && commands.contains(kit.penny.clientbus.crm.api.CrmCommand.Type.MOVE)
                    || code.equals("CANCEL_APPOINTMENT") && commands.contains(kit.penny.clientbus.crm.api.CrmCommand.Type.CANCEL)
                    || code.equals("READ_AVAILABILITY") && (commands.contains(kit.penny.clientbus.crm.api.CrmCommand.Type.CREATE) || commands.contains(kit.penny.clientbus.crm.api.CrmCommand.Type.MOVE));
            boolean command = code.endsWith("_APPOINTMENT") || code.equals("READ_AVAILABILITY");
            String state = !implemented ? "UNVERIFIED" : !row.isEnabled() || command && row.isArchived() || row.getConnection().getStatus() == CrmConnectionStatus.DISABLED
                    || command && (row.getZoneId()==null || row.getConnection().getStatus()!=CrmConnectionStatus.CONNECTED && row.getConnection().getStatus()!=CrmConnectionStatus.REAUTH_REQUIRED)
                    ? "CONFIGURATION_REQUIRED" : row.getConnection().getStatus() == CrmConnectionStatus.REAUTH_REQUIRED
                    ? "REAUTH_REQUIRED" : "AVAILABLE";
            capabilities.add(new CrmCapabilityDto(code, state, implemented ? null : "NOT_IMPLEMENTED"));
        }
        return new CrmProjectDto(row.getId(), row.getConnection().getId(), row.getWorkspace().getId(), row.getProvider(),
                row.getExternalId(), row.getDisplayName(), row.getCurrency(), row.getZoneId(), row.getZoneSource(),
                row.getZoneRevision(), row.getContextVersion(), row.isEnabled(), row.getConnection().getStatus(), capabilities, row.getVersion(), row.isArchived());
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
            case RATE_LIMITED,COOLDOWN -> 429;
            case TEMPORARY_FAILURE, CONNECTION_BUSY -> 503;
            case INVALID_RESPONSE, CONTEXT_MISMATCH -> 502;
            default -> 409;
        };
        return new CrmException(status, code, switch (error.code()) {
            case AUTH_REQUIRED -> "Не удалось авторизоваться в DIKIDI. Проверьте вход с номером и паролем, указанными в подключении.";
            case ACCESS_DENIED -> "У подключения нет доступа к выбранным данным DIKIDI.";
            case RATE_LIMITED -> "Лимит обращений к CRM. Повторите позже.";
            case COOLDOWN -> "DIKIDI временно приостановил обращения. Повторите после указанного времени.";
            case CONNECTION_BUSY -> "Подключение CRM занято другим запросом. Повторите позже.";
            case OBSERVATION_CONFLICT -> "Данные CRM изменились во время чтения. Повторите обновление периода.";
            case TEMPORARY_FAILURE -> "CRM временно недоступна.";
            case INVALID_RESPONSE, CONTEXT_MISMATCH -> "Ответ CRM не прошёл проверку.";
            default -> "Возможность CRM пока недоступна.";
        },error.retryAt());
    }
    void requireNetwork(CrmProjectEntity project) {
        var retry=registry.require(project.getProvider()).retryAt(project.getConnection().getId());
        if(retry!=null&&retry.isAfter(java.time.Instant.now()))throw failure(new CrmFailure(CrmFailure.Code.COOLDOWN,retry,true));
    }
    kit.penny.clientbus.common.dto.crm.CrmSyncStateDto pauseState(CrmProjectEntity project,kit.penny.clientbus.common.dto.crm.CrmSyncStateDto state) {
        return state.withRetryAt(registry.require(project.getProvider()).retryAt(project.getConnection().getId()));
    }
}
