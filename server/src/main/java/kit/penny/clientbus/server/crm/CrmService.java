package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.data.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;

@Service
public class CrmService {
    private final CrmStore store;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final CrmCatalogSync sync;
    private final TransactionTemplate tx;
    private final UiDomainEvents events;
    public CrmService(CrmStore store, CrmRegistry registry, CrmSecretCipher cipher, CrmCatalogSync sync,
                      PlatformTransactionManager manager, UiDomainEvents events) {
        this.store = store; this.registry = registry; this.cipher = cipher; this.sync = sync;
        this.tx = new TransactionTemplate(manager); this.events = events;
    }
    public List<CrmType> types() { store.guard.admin(); return AvailableCrmType.types(); }
    public List<CrmConnectionDto> connections() {
        store.storage.requireReady();
        return tx.execute(status -> store.connections.findAllByOrganizationIdOrderByCreatedAtAsc(store.guard.admin())
                .stream().map(CrmStore::dto).toList());
    }
    public CrmConnectionDto connection(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> CrmStore.dto(store.connection(id, store.guard.admin())));
    }
    public CrmConnectionDto createConnection(CreateCrmConnectionRequest request) {
        store.storage.requireReady();
        store.guard.admin();
        registry.require(request.provider()); // Before saving/encrypting a secret or doing any I/O.
        String login = login(request.login());
        if (request.secret() == null || request.secret().isBlank() || request.secret().length() > 512) throw CrmException.invalid();
        return connectionWrite(() -> tx.execute(status -> {
            UUID organization = store.guard.admin();
            // Also protects upgraded databases whose pre-existing duplicates prevent Hibernate's unique DDL.
            store.em.find(OrganizationEntity.class, organization, LockModeType.PESSIMISTIC_WRITE);
            if (store.connections.existsByOrganizationIdAndProviderAndLogin(organization, request.provider(), login)) throw duplicateLogin();
            if (!cipher.configured()) throw new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Настройте ключ шифрования CRM на сервере.");
            var row = new CrmConnectionEntity();
            row.setOrganization(store.em.getReference(OrganizationEntity.class, organization));
            row.setProvider(request.provider()); row.setLogin(login); row.setStatus(CrmConnectionStatus.CONFIGURED);
            row.setDisplayName(displayName(request.displayName()));
            row.setSecretKeyId(cipher.keyId()); row.setSecretCiphertext(new byte[0]);
            store.em.persist(row);
            row.setSecretCiphertext(cipher.encrypt(organization, row.getId(), request.secret()));
            store.em.flush();
            events.organization(organization, "crm.invalidated");
            return CrmStore.dto(row);
        }));
    }
    public CrmConnectionDto updateConnection(UUID id, UpdateCrmConnectionRequest request) {
        store.storage.requireReady();
        return connectionWrite(() -> tx.execute(status -> {
            UUID organization = store.guard.admin();
            store.em.find(OrganizationEntity.class, organization, LockModeType.PESSIMISTIC_WRITE);
            var row = store.connection(id, organization); store.em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            version(row, request.expectedVersion()); registry.require(row.getProvider());
            if (request.login() != null && store.connections.existsByOrganizationIdAndProviderAndLoginAndIdNot(
                    row.getOrganization().getId(), row.getProvider(), login(request.login()), id)) throw duplicateLogin();
            boolean renamed = request.displayName() != null && !Objects.equals(row.getDisplayName(), displayName(request.displayName()));
            if (renamed) row.setDisplayName(displayName(request.displayName()));
            if (request.login() != null && !login(request.login()).equals(row.getLogin()) && request.secret() == null)
                throw CrmException.invalid();
            boolean changed = false;
            if (request.secret() != null) {
                if (request.secret().isBlank()) throw CrmException.invalid();
                if (request.login() != null) row.setLogin(login(request.login()));
                row.setSecretCiphertext(cipher.encrypt(row.getOrganization().getId(), row.getId(), request.secret()));
                row.setSecretKeyId(cipher.keyId()); changed = true;
            }
            boolean wasEnabled = row.getStatus() != CrmConnectionStatus.DISABLED;
            if (request.enabled() != null && request.enabled() != wasEnabled) changed = true;
            if (changed) {
                row.setCredentialVersion(row.getCredentialVersion()+1);
                boolean enabled = request.enabled() == null ? wasEnabled : request.enabled();
                row.setStatus(enabled ? CrmConnectionStatus.CONFIGURED : CrmConnectionStatus.DISABLED);
                row.setLastCheckedAt(null); row.setLastErrorCode(null);
                for (var project : store.projects.findAllByConnectionId(id)) {
                    store.em.lock(project, LockModeType.PESSIMISTIC_WRITE);
                    sync.invalidateProject(project.getId());
                    events.workspace(project.getWorkspace(), "crm.invalidated");
                }
                registry.invalidate(id);
            }
            if (changed || renamed) events.organization(row.getOrganization().getId(), "crm.invalidated");
            store.em.flush(); return CrmStore.dto(row);
        }));
    }
    private static CrmException duplicateLogin() {
        return new CrmException(409, "CRM_CONNECTION_LOGIN_CONFLICT", "Аккаунт этой CRM с таким логином уже добавлен в организацию, возможно, он отключён. Используйте существующий аккаунт.");
    }
    private static <T> T connectionWrite(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "uqconnectionlogin".equals(violation.getConstraintName())) throw duplicateLogin();
            }
            throw failure;
        }
    }
    private CrmCredentials adminCredentials(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> store.credentials(store.connection(id, store.guard.admin())));
    }
    private <T> T read(CrmCredentials credentials, Function<CrmAccess, T> action) {
        try (var access = credentials.open(cipher)) { return action.apply(access); }
        catch (CrmFailure error) {
            health(credentials, error.code() == CrmFailure.Code.AUTH_REQUIRED ? CrmConnectionStatus.REAUTH_REQUIRED : CrmConnectionStatus.DEGRADED,
                    CrmStore.failure(error).code());
            throw CrmStore.failure(error);
        }
    }
    private void health(CrmCredentials credentials, CrmConnectionStatus status, String errorCode) {
        tx.executeWithoutResult(transaction -> {
            store.guard.admin(); store.requireCredentials(credentials);
            var row = store.connection(credentials.id, credentials.organizationId);
            row.setStatus(status); row.setLastCheckedAt(Instant.now()); row.setLastErrorCode(errorCode);
            events.organization(row.getOrganization().getId(), "crm.invalidated");
        });
    }
    public CrmConnectionDto verify(UUID id) {
        var credentials = adminCredentials(id);
        read(credentials, access -> { registry.require(credentials.provider).verifyConnection(access); return true; });
        health(credentials, CrmConnectionStatus.CONNECTED, null);
        return connection(id);
    }
    public CrmProjectCheckDto check(UUID connectionId, CheckCrmProjectRequest request) {
        var credentials = adminCredentials(connectionId);
        String externalId = externalId(request.externalProjectId());
        var result = inspect(credentials, externalId);
        tx.executeWithoutResult(status -> { store.guard.admin(); store.requireCredentials(credentials); });
        health(credentials, CrmConnectionStatus.CONNECTED, null);
        return new CrmProjectCheckDto(result.externalId(), result.displayName(), result.currency(), result.zoneId());
    }
    private CrmProjectInfo inspect(CrmCredentials credentials, String externalId) {
        return read(credentials, access -> {
            var info = registry.require(credentials.provider).inspectProject(access, externalId);
            if (info == null || !externalId.equals(info.externalId()))
                throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
            return info;
        });
    }
    public List<CrmProjectDto> projects(UUID workspaceId, CrmType provider) {
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization = store.guard.organization(); store.guard.workspace(workspaceId, false);
            return store.projects.findAllByWorkspaceIdAndOrganizationIdOrderByCreatedAtAsc(workspaceId, organization).stream()
                    .filter(row -> provider == null || row.getProvider() == provider).map(CrmStore::dto).toList();
        });
    }
    public List<CrmProjectDto> organizationProjects() {
        store.storage.requireReady();
        return tx.execute(status -> store.projects.findAllByOrganizationIdOrderByCreatedAtAsc(store.guard.admin())
                .stream().map(CrmStore::dto).toList());
    }
    public void requireProjectAccess(UUID projectId) {
        store.storage.requireReady();
        tx.executeWithoutResult(status -> store.project(projectId, store.guard.organization()));
    }
    public record Binding(CrmProjectDto project, boolean created) {}
    public Binding bind(UUID workspaceId, BindCrmProjectRequest request) {
        var credentials = adminCredentials(request.connectionId());
        tx.executeWithoutResult(status -> store.guard.workspace(workspaceId, false));
        String externalId = externalId(request.externalProjectId());
        String zone = zone(request.zoneId());
        CrmProjectInfo info = inspect(credentials, externalId);
        Binding result;
        try {
            result = tx.execute(status -> {
                UUID organization = store.guard.admin(); store.guard.workspace(workspaceId, false);
                var workspace = store.em.find(WorkspaceEntity.class, workspaceId, LockModeType.PESSIMISTIC_WRITE);
                if (workspace == null || !organization.equals(workspace.getOrganization().getId())) throw CrmStore.notFound();
                store.requireCredentials(credentials);
                var existing = store.projects.findByOrganizationIdAndProviderAndExternalId(organization, credentials.provider, externalId);
                if (existing.isPresent()) {
                    var row = existing.get();
                    if (!workspaceId.equals(row.getWorkspace().getId()) || !credentials.id.equals(row.getConnection().getId()))
                        throw CrmException.conflict("CRM_PROJECT_BINDING_CONFLICT");
                    return new Binding(CrmStore.dto(row), false);
                }
                if (credentials.provider == CrmType.DIKIDI && store.projects.existsByWorkspaceIdAndProvider(workspaceId, CrmType.DIKIDI))
                    throw CrmException.conflict("CRM_PROJECT_BINDING_CONFLICT");
                var row = new CrmProjectEntity();
                row.setOrganization(workspace.getOrganization()); row.setWorkspace(workspace);
                row.setConnection(store.connection(credentials.id, organization)); row.setProvider(credentials.provider);
                row.setExternalId(externalId); row.setDisplayName(info.displayName()); row.setCurrency(info.currency());
                row.setZoneId(zone); row.setZoneSource(zone == null ? null : "MANUAL");
                store.em.persist(row); store.em.flush(); events.workspace(workspace, "crm.invalidated");
                return new Binding(CrmStore.dto(row), true);
            });
        } catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException error) {
            throw CrmException.conflict("CRM_PROJECT_BINDING_CONFLICT");
        }
        if (result.created()) startInitialRead(result.project().id());
        return result;
    }
    private void startInitialRead(UUID projectId) {
        try { sync.refresh(projectId); }
        catch (CrmException ignored) { /* Binding is committed; refresh failures remain independently retryable. */ }
    }
    public CrmProjectDto updateProject(UUID projectId, UpdateCrmProjectRequest request) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var project = store.project(projectId, store.guard.admin()); store.em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
            version(project, request.expectedVersion());
            boolean changed = false;
            if (request.enabled() != null && request.enabled() != project.isEnabled()) { project.setEnabled(request.enabled()); changed = true; }
            if (request.zoneId() != null) {
                String zone = zone(request.zoneId());
                if (!Objects.equals(zone, project.getZoneId())) {
                    project.setZoneId(zone); project.setZoneSource(zone == null ? null : "MANUAL");
                    project.setZoneRevision(project.getZoneRevision()+1); changed = true;
                }
            }
            if (changed) {
                project.setContextVersion(project.getContextVersion()+1); sync.invalidateProject(projectId);
                events.workspace(project.getWorkspace(), "crm.invalidated");
            }
            store.em.flush(); return CrmStore.dto(project);
        });
    }
    public CrmProjectDto changeConnection(UUID projectId, ChangeCrmConnectionRequest request) {
        store.storage.requireReady();
        CrmContext context = tx.execute(status -> {
            var project = store.project(projectId, store.guard.admin());
            version(project, request.expectedVersion()); return store.context(project);
        });
        CrmCredentials credentials = adminCredentials(request.connectionId());
        if (!context.provider().equals(credentials.provider.name())) throw CrmException.conflict("CRM_PROJECT_BINDING_CONFLICT");
        CrmProjectInfo info = inspect(credentials, context.externalProjectId());
        var result = tx.execute(status -> {
            store.guard.admin(); store.requireCredentials(credentials);
            var project = store.project(projectId, credentials.organizationId);
            store.em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
            version(project, request.expectedVersion());
            if (!context.equals(store.context(project))) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            if (!project.getConnection().getId().equals(credentials.id)) {
                project.setConnection(store.connection(credentials.id, credentials.organizationId));
                project.setContextVersion(project.getContextVersion()+1); sync.invalidateProject(projectId);
                project.setDisplayName(info.displayName()); project.setCurrency(info.currency());
                events.workspace(project.getWorkspace(), "crm.invalidated");
            }
            store.em.flush(); return CrmStore.dto(project);
        });
        if (result.enabled()) startInitialRead(projectId);
        return result;
    }
    public CrmMasterPageDto masters(UUID projectId, String cursor) {
        return masters(projectId, cursor, null, false, true);
    }
    public CrmMasterDto master(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var row = store.masters.findById(id).orElseThrow(CrmStore::notFound);
            store.project(row.getProject().getId(), store.guard.organization());
            return CrmStore.dto(row);
        });
    }
    public CrmMasterPageDto masters(UUID projectId, String cursor, String query, boolean unlinked, boolean includeNotObserved) {
        store.storage.requireReady();
        String search = CrmSearch.normalize(query);
        String scope = CrmSearch.scope(projectId + "/" + search + "/" + unlinked + "/" + includeNotObserved);
        return tx.execute(status -> {
            var project = store.project(projectId, store.guard.organization());
            if (unlinked) store.guard.workspace(project.getWorkspace().getId(), true);
            // Keep rows and their source observation together while a catalog writer waits.
            store.em.refresh(project, LockModeType.PESSIMISTIC_READ);
            long revision = sync.revision(projectId);
            UUID afterId = decodeCursor(scope, cursor, revision);
            var page = PageRequest.of(0, 100, Sort.by("externalId", "id"));
            Slice<CrmMasterEntity> rows;
            var after = afterId == null ? null : store.masters.findById(afterId).filter(row -> row.getProject().getId().equals(projectId))
                    .orElseThrow(() -> CrmException.conflict("CRM_CONTEXT_CHANGED"));
            if (search.isEmpty() && !unlinked && includeNotObserved) {
                rows = after == null ? store.masters.findAllByProjectId(projectId, page)
                        : store.masters.findAllByProjectIdAndExternalIdGreaterThan(projectId, after.getExternalId(), page);
            } else rows = store.masters.findAll((root, criteria, cb) -> {
                var filters = new ArrayList<jakarta.persistence.criteria.Predicate>();
                filters.add(cb.equal(root.get("project").get("id"), projectId));
                if (after != null) filters.add(cb.greaterThan(root.get("externalId"), after.getExternalId()));
                if (!search.isEmpty()) filters.add(cb.like(cb.lower(root.get("displayName")), CrmSearch.pattern(search), '!'));
                if (unlinked) filters.add(cb.isNull(root.get("employee")));
                if (!includeNotObserved) filters.add(cb.or(cb.isNull(root.get("observationState")), cb.notEqual(root.get("observationState"), "NOT_OBSERVED")));
                return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
            }, page);
            return new CrmMasterPageDto(rows.getContent().stream().map(CrmStore::dto).toList(),
                    rows.hasNext() ? encodeCursor(scope, revision, rows.getContent().getLast().getId()) : null,
                    rows.hasNext(), sync.state(projectId), revision);
        });
    }
    public CrmMasterDto linkEmployee(UUID masterId, UUID employeeId, long expectedVersion) {
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization = store.guard.organization(), actor = store.guard.actor();
            var original = store.masters.findById(masterId).orElseThrow(CrmStore::notFound);
            var project = original.getProject();
            if (!organization.equals(project.getOrganization().getId())) throw CrmStore.notFound();
            store.guard.workspace(project.getWorkspace().getId(), true);
            if (employeeId != null) store.guard.employee(employeeId);
            store.lockMappingEmployees(actor, employeeId);
            var author = store.em.find(EmployeeEntity.class, actor);
            if (author == null) throw CrmStore.notFound();
            EmployeeEntity employee = null;
            if (employeeId != null) {
                employee = store.em.find(EmployeeEntity.class, employeeId);
                if (employee == null || !organization.equals(employee.getOrganization().getId())) throw CrmStore.notFound();
            }
            store.em.refresh(original, LockModeType.PESSIMISTIC_WRITE);
            version(original, expectedVersion);
            UUID previous = original.getEmployee() == null ? null : original.getEmployee().getId();
            if (!Objects.equals(previous, employeeId)) {
                original.setEmployee(employee); original.setMappingPreviousId(previous);
                original.setMappingUpdatedBy(author); original.setMappingUpdatedAt(Instant.now());
                original.setMappingReason(employeeId == null ? "UNLINK" : previous == null ? "LINK" : "RELINK");
                events.workspace(project.getWorkspace(), "crm.invalidated");
            }
            store.em.flush(); return CrmStore.dto(original);
        });
    }
    public List<CrmEmployeeCandidateDto> employeeCandidates(UUID projectId) {
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization = store.guard.organization();
            var project = store.project(projectId, organization);
            store.guard.workspace(project.getWorkspace().getId(), true);
            return store.em.createQuery("select e from EmployeeEntity e join fetch e.user where e.organization.id = :org", EmployeeEntity.class)
                    .setParameter("org", organization).getResultList().stream()
                    .filter(row -> row.getUser().getRole() == UserRole.EMPLOYEE)
                    .filter(row -> store.guard.canReadEmployee(row.getId()))
                    .map(row -> new CrmEmployeeCandidateDto(row.getId(),
                            String.join(" ", Objects.toString(row.getFirstName(), ""), Objects.toString(row.getLastName(), "")).strip(),
                            row.getUser().getUsername(), row.getUser().isEnabled()))
                    .sorted(Comparator.comparing(CrmEmployeeCandidateDto::displayName).thenComparing(CrmEmployeeCandidateDto::username)).toList();
        });
    }
    private static void version(CrmRecord row, Long expected) {
        if (expected == null || expected < 0) throw CrmException.invalid();
        if (row.getVersion() != expected) throw CrmException.conflict("CRM_VERSION_CONFLICT");
    }
    private static String login(String value) {
        if (value == null || !value.trim().matches("\\+?[0-9]{10,15}")) throw CrmException.invalid();
        return value.trim().replace("+", "");
    }
    private static String displayName(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 80) throw CrmException.invalid();
        return value.strip();
    }
    private static String externalId(String value) {
        if (value == null || !value.trim().matches("[1-9][0-9]{0,19}")) throw CrmException.invalid();
        return value.trim();
    }
    private static String zone(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (!ZoneId.getAvailableZoneIds().contains(value.trim())) throw CrmException.invalid();
            return ZoneId.of(value.trim()).getId();
        } catch (DateTimeException error) { throw CrmException.invalid(); }
    }
    private static String encodeCursor(String scope, long revision, UUID lastMasterId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("3:"+scope+":"+revision+":"+lastMasterId).getBytes(StandardCharsets.US_ASCII));
    }
    private static UUID decodeCursor(String scope, String cursor, long revision) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 200) throw CrmException.invalid();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":", -1);
            // Old offset cursors cannot identify a snapshot: restart the read after an upgrade.
            if ((parts.length == 3 && "1".equals(parts[0])) || (parts.length == 4 && "2".equals(parts[0])))
                throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            if (parts.length != 4 || !"3".equals(parts[0]) || !scope.equals(parts[1])) throw CrmException.invalid();
            long expected = Long.parseLong(parts[2]);
            if (expected < 0) throw CrmException.invalid();
            UUID afterId = UUID.fromString(parts[3]);
            if (expected != revision) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            return afterId;
        } catch (IllegalArgumentException error) { throw CrmException.invalid(); }
    }
}
