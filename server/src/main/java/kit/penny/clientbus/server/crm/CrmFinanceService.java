package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** On-demand, bounded I/O outside the database transaction; FINANCE owns financial fields only. */
@Service
public class CrmFinanceService {
    private record Claim(UUID appointmentId,long appointmentVersion,CrmContext context,CrmFinanceQuery query,
            UUID actor,UUID token,long generation,Instant expiresAt,CrmCredentials credentials,CrmHealthCheck health) {}
    private final CrmStore store;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    public CrmFinanceService(CrmStore store,CrmRegistry registry,CrmSecretCipher cipher,
            UiDomainEvents events,PlatformTransactionManager manager,JdbcTemplate jdbc) {
        this.store=store; this.registry=registry; this.cipher=cipher; this.events=events;
        tx=new TransactionTemplate(manager); this.jdbc=jdbc;
    }
    public CrmFinanceDto read(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var row=appointment(id);
            var project=store.project(row.getProject().getId(),store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ);
            store.guard.workspace(project.getWorkspace().getId(),false); store.em.refresh(row);
            return dto(row,state(row,false));
        });
    }
    public CrmFinanceDto refresh(UUID id) {
        store.storage.requireReady();
        Claim claim=tx.execute(status -> {
            var row=appointment(id);
            var visible=store.project(row.getProject().getId(),store.guard.organization());
            var project=store.requireContext(store.context(visible));
            store.guard.workspace(project.getWorkspace().getId(),false);
            store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
            if (!"APPOINTMENT".equals(row.getKind())) throw CrmStore.failure(new CrmFailure(CrmFailure.Code.CAPABILITY_UNVERIFIED));
            var credentials=store.credentials(project.getConnection());
            store.requireNetwork(project);
            var control=control(project,true); Instant now=now();
            if (control.getClaimUntil()!=null && control.getClaimUntil().isAfter(now))
                throw new CrmException(429,"CRM_CONNECTION_BUSY","Финансы проекта уже обновляются.");
            var state=state(row,true);
            var query=new CrmFinanceQuery(row.getExternalId(),row.getStartLocal().toLocalDate(),
                    row.getExternalClientId()==null ? null : new CrmClientReference(row.getClientIdentityKind(),row.getExternalClientId()));
            UUID token=UUID.randomUUID(); state.setGeneration(Math.incrementExact(Math.max(control.getGeneration(),row.getFinancialGeneration())));
            state.setClaimToken(token); state.setClaimUntil(now.plusSeconds(120)); state.setStatus("RUNNING");
            var context=store.context(project);
            state.setConnectionId(context.connectionId()); state.setCredentialVersion(context.credentialVersion());
            state.setContextVersion(context.contextVersion()); state.setZoneRevision(context.zoneRevision());
            state.setScope(Map.of("appointmentId",id.toString())); state.setLastAttemptAt(now); state.setLastErrorCode(null);
            state.setAttemptCoverage(Map.of());
            control.setGeneration(state.getGeneration()); control.setClaimToken(token); control.setClaimUntil(state.getClaimUntil());
            control.setStatus("RUNNING"); control.setLastAttemptAt(now);
            control.setConnectionId(context.connectionId()); control.setCredentialVersion(context.credentialVersion());
            control.setContextVersion(context.contextVersion()); control.setZoneRevision(context.zoneRevision());
            return new Claim(id,row.getVersion(),context,query,store.guard.actor(),token,state.getGeneration(),
                    state.getClaimUntil(),credentials,CrmHealthCheck.begin(project.getConnection()));
        });
        try {
            CrmFinanceSnapshot snapshot;
            try (var access=claim.credentials().open(cipher)) {
                snapshot=registry.require(claim.credentials().provider).readFinance(access,claim.context().externalProjectId(),claim.query());
            }
            validate(snapshot,claim.context().externalProjectId(),claim.query());
            tx.executeWithoutResult(status -> {
                var project=store.requireContext(claim.context());
                store.guard.workspace(claim.actor(),project.getWorkspace().getId());
                var row=appointment(id); store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
                var state=requireClaim(row,claim);
                if (row.getVersion()!=claim.appointmentVersion() || row.getFinancialGeneration()>=claim.generation())
                    throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                row.setFinancialSnapshot(document(snapshot));
                row.setFinancialObservedAt(snapshot.observedAt()); row.setFinancialCompleteness(snapshot.completeness().name());
                row.setFinancialGeneration(claim.generation()); row.setPaymentState(paymentState(snapshot));
                state.setLastSuccessAt(snapshot.observedAt()); state.setStatus(snapshot.completeness()==CrmReadResult.Completeness.COMPLETE ? "SUCCEEDED" : "PARTIAL");
                state.setClaimToken(null); state.setClaimUntil(null);
                releaseControl(project,"SUCCEEDED");
                var coverage=Map.<String,Object>of("completeness",snapshot.completeness().name(),"observedAt",snapshot.observedAt().toString());
                state.setAttemptCoverage(coverage);
                if (snapshot.completeness()==CrmReadResult.Completeness.COMPLETE) state.setSuccessfulCoverage(coverage);
                claim.health().apply(project.getConnection(),CrmConnectionStatus.CONNECTED,null);
                events.workspace(project.getWorkspace(),"crm.invalidated");
                store.em.flush();
                if (!now().isBefore(claim.expiresAt())) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            });
        } catch (CrmFailure error) {
            var failure=CrmStore.failure(error); fail(claim,failure.code()); throw failure;
        } catch (RuntimeException error) {
            fail(claim,error instanceof CrmException crm ? crm.code() : "CRM_TEMPORARILY_UNAVAILABLE");
            if (error instanceof CrmException || error instanceof org.springframework.security.access.AccessDeniedException) throw error;
            throw CrmStore.failure(new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE));
        }
        return read(id);
    }
    private void fail(Claim claim,String code) {
        try { tx.executeWithoutResult(status -> {
            var project=store.requireContext(claim.context());
            var row=appointment(claim.appointmentId()); store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
            var state=requireClaim(row,claim); state.setStatus("FAILED"); state.setLastErrorCode(code);
            state.setClaimToken(null); state.setClaimUntil(null);
            releaseControl(project,"FAILED");
            if ("CRM_REAUTH_REQUIRED".equals(code)) claim.health().apply(project.getConnection(),CrmConnectionStatus.REAUTH_REQUIRED,code);
            events.workspace(project.getWorkspace(),"crm.invalidated");
        }); } catch (RuntimeException ignored) { /* A changed context/expired lease must not publish old results. */ }
    }
    private CrmSyncStateEntity requireClaim(CrmAppointmentEntity row,Claim claim) {
        var state=state(row,false);
        var control=control(row.getProject(),false);
        if (state==null || state.getGeneration()!=claim.generation() || !claim.token().equals(state.getClaimToken())
                || control==null || control.getGeneration()!=claim.generation() || !claim.token().equals(control.getClaimToken())
                || control.getClaimUntil()==null || !control.getClaimUntil().isAfter(now())
                || state.getClaimUntil()==null || !state.getClaimUntil().isAfter(now())) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        return state;
    }
    private CrmAppointmentEntity appointment(UUID id) {
        var row=store.em.find(CrmAppointmentEntity.class,id); if (row==null) throw CrmStore.notFound(); return row;
    }
    private CrmSyncStateEntity state(CrmAppointmentEntity row,boolean create) {
        var rows=store.em.createQuery("from CrmSyncStateEntity where project.id=:project and dataset='FINANCE' and scopeKey=:scope",CrmSyncStateEntity.class)
                .setParameter("project",row.getProject().getId()).setParameter("scope",row.getId().toString()).getResultList();
        if (!rows.isEmpty()) return rows.getFirst();
        if (!create) return null;
        var state=initial(row.getProject()); state.setDataset("FINANCE");
        state.setStateKind("SCOPE"); state.setWriteDomain("FINANCE"); state.setScopeKey(row.getId().toString());
        state.setStatus("IDLE"); store.em.persist(state); return state;
    }
    private CrmSyncStateEntity control(CrmProjectEntity project,boolean create) {
        var rows=store.em.createQuery("from CrmSyncStateEntity where project.id=:project and dataset='WRITER:FINANCE' and scopeKey='CONTROL'",CrmSyncStateEntity.class)
                .setParameter("project",project.getId()).getResultList();
        if (!rows.isEmpty()) return rows.getFirst();
        if (!create) return null;
        var state=initial(project); state.setDataset("WRITER:FINANCE"); state.setScopeKey("CONTROL");
        state.setStateKind("CONTROL"); state.setStatus("IDLE"); store.em.persist(state); return state;
    }
    private CrmSyncStateEntity initial(CrmProjectEntity project) {
        var context=store.context(project); var state=new CrmSyncStateEntity(); state.setProject(project);
        state.setWriteDomain("FINANCE"); state.setConnectionId(context.connectionId()); state.setCredentialVersion(context.credentialVersion());
        state.setContextVersion(context.contextVersion()); state.setZoneRevision(context.zoneRevision()); return state;
    }
    private void releaseControl(CrmProjectEntity project,String status) {
        var control=control(project,false); control.setStatus(status); control.setClaimToken(null); control.setClaimUntil(null);
    }
    private static Map<String,Object> document(CrmFinanceSnapshot snapshot) {
        Map<String,Object> flat=CrmFinancialJson.object(snapshot);
        var result=new LinkedHashMap<String,Object>(); result.put("schemaVersion",1);
        result.put("saleReference",flat.get("externalSaleId")); result.put("scope",flat.get("scope"));
        result.put("externalProjectId",snapshot.externalProjectId()); result.put("lines",flat.get("lines")); result.put("payments",flat.get("payments"));
        var totals=new LinkedHashMap<String,Object>();
        for (String key:List.of("cost","paid","debt","currency","headDiscount","headBonus")) totals.put(key,flat.get(key));
        result.put("totals",totals);
        result.put("warnings",snapshot.completeness()==CrmReadResult.Completeness.COMPLETE ? List.of() : List.of("FINANCE_COMPLETENESS_UNVERIFIED"));
        result.put("sections",Map.of("lines",snapshot.completeness().name(),"totals",snapshot.completeness().name(),"payments",snapshot.completeness().name()));
        return result;
    }
    private Instant now() { return jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant(); }
    private CrmFinanceDto dto(CrmAppointmentEntity row,CrmSyncStateEntity state) {
        var project=row.getProject(); var connection=project.getConnection();
        String status=state==null ? "IDLE" : state.getStatus(),error=state==null ? null : state.getLastErrorCode();
        if (state!=null && "RUNNING".equals(status) && (state.getClaimUntil()==null || !state.getClaimUntil().isAfter(now()))) {
            status="FAILED"; error="CRM_REFRESH_EXPIRED";
        }
        boolean current=state!=null && project.isEnabled() && connection.getStatus()==CrmConnectionStatus.CONNECTED
                && connection.getId().equals(state.getConnectionId()) && connection.getCredentialVersion()==state.getCredentialVersion()
                && project.getContextVersion()==state.getContextVersion() && project.getZoneRevision()==state.getZoneRevision()
                && Set.of("SUCCEEDED","PARTIAL").contains(status) && scopeMatches(row) && row.getFinancialObservedAt()!=null
                && row.getFinancialObservedAt().isAfter(Instant.now().minusSeconds(300));
        var sync=new CrmSyncStateDto("FINANCE",status,state==null ? null : state.getLastAttemptAt(),row.getFinancialObservedAt(),
                row.getFinancialObservedAt()==null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",row.getFinancialCompleteness(),
                error,state==null ? 0 : state.getGeneration(),Map.of("appointmentId",row.getId().toString()),
                state==null ? Map.of() : state.getSuccessfulCoverage(),state==null ? Map.of() : state.getAttemptCoverage());
        return new CrmFinanceDto(row.getId(),row.getFinancialSnapshot(),row.getFinancialObservedAt(),row.getFinancialCompleteness(),store.pauseState(project,sync));
    }
    private static boolean scopeMatches(CrmAppointmentEntity row) {
        if (!(row.getFinancialSnapshot().get("scope") instanceof Map<?,?> scope)
                || !row.getExternalId().equals(scope.get("externalAppointmentId"))
                || !row.getStartLocal().toLocalDate().toString().equals(scope.get("date"))) return false;
        if (row.getExternalClientId()==null) return scope.get("client")==null;
        return scope.get("client") instanceof Map<?,?> client && row.getExternalClientId().equals(client.get("externalId"))
                && Objects.equals(row.getClientIdentityKind(),client.get("identityKind"));
    }
    static void validate(CrmFinanceSnapshot snapshot,String project,CrmFinanceQuery query) {
        if (snapshot==null || !project.equals(snapshot.externalProjectId()) || !query.equals(snapshot.scope()))
            throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
        if (snapshot.observedAt()==null || snapshot.observedAt().isAfter(Instant.now().plusSeconds(300)) || snapshot.completeness()==null
                || snapshot.lines().size()>200 || snapshot.payments().size()>50) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        if (snapshot.currency()!=null && !snapshot.currency().matches("[A-Z]{3}")) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        for (BigDecimal amount:Arrays.asList(snapshot.cost(),snapshot.paid(),snapshot.debt(),snapshot.headDiscount(),snapshot.headBonus()))
            money(amount);
        var lineIds=new HashSet<String>();
        for (var line:snapshot.lines()) {
            identifier(line.externalLineId()); identifier(line.externalMasterId()); identifier(line.externalServiceId());
            if (!lineIds.add(line.externalLineId())) invalid();
            for (var amount:Arrays.asList(line.price(),line.discountPercent(),line.discountAmount(),line.total())) money(amount);
            if (line.discountPercent()!=null && line.discountPercent().compareTo(BigDecimal.valueOf(100))>0) invalid();
        }
        var paymentIds=new HashSet<String>();
        for (var payment:snapshot.payments()) {
            identifier(payment.externalPaymentId());
            if (!paymentIds.add(payment.externalPaymentId()) || payment.components().size()>50
                    || payment.localDate()!=null && (payment.localDate().length()>64 || payment.localDate().chars().anyMatch(Character::isISOControl))) invalid();
            for (var component:payment.components()) {
                identifier(component.method()); identifier(component.externalMethodId()); money(component.amount());
                if (component.amount()==null) invalid();
            }
        }
        if (snapshot.externalSaleId()!=null) identifier(snapshot.externalSaleId());
        else if (!snapshot.lines().isEmpty() || !snapshot.payments().isEmpty()) invalid();
    }
    private static void identifier(String value) { if (value==null || !value.matches("[a-zA-Z0-9_-]{1,128}")) invalid(); }
    private static void money(BigDecimal amount) {
        if (amount!=null && (amount.signum()<0 || amount.precision()>19 || amount.scale()>4 || amount.scale()<-15)) invalid();
    }
    private static void invalid() { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
    static String paymentState(CrmFinanceSnapshot snapshot) {
        if (snapshot.cost()==null || snapshot.paid()==null || snapshot.debt()==null || snapshot.currency()==null) return "UNKNOWN";
        if (snapshot.cost().compareTo(snapshot.paid().add(snapshot.debt()))!=0) return "UNKNOWN";
        if (snapshot.debt().signum()==0) return "PAID";
        return snapshot.paid().signum()==0 ? "UNPAID" : "PARTIALLY_PAID";
    }
}
