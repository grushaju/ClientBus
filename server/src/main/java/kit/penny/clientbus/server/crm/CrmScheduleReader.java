package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.server.persistence.entity.crm.CrmWorkIntervalEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class CrmScheduleReader {
    private record Cursor(LocalDateTime start,LocalDateTime end,UUID id) {}
    private final CrmStore store;
    private final CrmScheduleSync sync;
    private final TransactionTemplate tx;
    public CrmScheduleReader(CrmStore store,CrmScheduleSync sync,PlatformTransactionManager manager) {
        this.store=store; this.sync=sync; tx=new TransactionTemplate(manager);
    }
    public CrmWorkIntervalPageDto intervals(UUID projectId,UUID masterId,LocalDate from,LocalDate to,String cursor) {
        var scope=new CrmScheduleScope(masterId,from,to);
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=store.project(projectId,store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ); sync.master(projectId,masterId);
            long revision=sync.revision(projectId);
            String hash=CrmSearch.scope(projectId+"/"+scope.key());
            Cursor after=decode(cursor,hash,revision);
            var query=store.em.createQuery("""
                    select w from CrmWorkIntervalEntity w where w.project.id=:project and w.master.id=:master
                        and w.kind='WORK' and w.startLocal<:end and w.endLocal>:start
                    """+(after == null ? "" : " and (w.startLocal>:lastStart or w.startLocal=:lastStart and (w.endLocal>:lastEnd or w.endLocal=:lastEnd and w.id>:lastId))")
                    +" order by w.startLocal,w.endLocal,w.id",CrmWorkIntervalEntity.class)
                    .setParameter("project",projectId).setParameter("master",masterId)
                    .setParameter("start",from.atStartOfDay()).setParameter("end",to.plusDays(1).atStartOfDay()).setMaxResults(101);
            if (after != null) query.setParameter("lastStart",after.start()).setParameter("lastEnd",after.end()).setParameter("lastId",after.id());
            var rows=query.getResultList();
            boolean more=rows.size()>100;
            var page=rows.subList(0,Math.min(100,rows.size()));
            String next=more ? encode(page.getLast(),hash,revision) : null;
            var items=page.stream().map(row -> new CrmWorkIntervalDto(row.getId(),projectId,masterId,row.getStartLocal(),row.getEndLocal(),
                    row.getKind(),row.getExternalRef(),Map.copyOf(row.getAttributes()),row.getLastObservedAt(),row.getVersion())).toList();
            return new CrmWorkIntervalPageDto(items,next,more,sync.state(project,scope),revision);
        });
    }
    private static String encode(CrmWorkIntervalEntity row,String scope,long revision) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+scope+"|"+revision+"|"+row.getStartLocal()+"|"+row.getEndLocal()+"|"+row.getId()).getBytes(StandardCharsets.UTF_8));
    }
    private static Cursor decode(String cursor,String scope,long revision) {
        if (cursor == null) return null;
        if (cursor.length()>1024) throw CrmException.invalid();
        try {
            String[] values=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if (values.length!=6 || !"1".equals(values[0]) || !scope.equals(values[1]) || Long.parseLong(values[2])!=revision)
                throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            var start=LocalDateTime.parse(values[3]); var end=LocalDateTime.parse(values[4]);
            if (start.getYear()<1999 || start.getYear()>2101 || end.getYear()<1999 || end.getYear()>2101
                    || !end.isAfter(start) || end.isAfter(start.plusDays(1))) throw CrmException.invalid();
            return new Cursor(start,end,UUID.fromString(values[5]));
        } catch (IllegalArgumentException | DateTimeException error) { throw CrmException.invalid(); }
    }
}
