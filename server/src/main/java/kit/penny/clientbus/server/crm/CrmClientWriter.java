package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Component
@Transactional(propagation=Propagation.MANDATORY)
public class CrmClientWriter {
    private final CrmStore store;
    public CrmClientWriter(CrmStore store) { this.store=store; }
    public void apply(CrmProjectEntity project, CrmClientPage page) { apply(project, page, Map.of()); }

    /** @param avatarKeys локальные ключи зеркала аватара (externalId → storageKey) для строк этой страницы. */
    public void apply(CrmProjectEntity project, CrmClientPage page, Map<String,String> avatarKeys) {
        Set<List<String>> seen=new HashSet<>();
        if (page.items().size()>1000) invalid();
        for (var source : page.items()) {
            if (source.externalId()==null || source.externalId().isBlank() || source.externalId().length()>128
                    || !"PROJECT".equals(source.identityKind()) || !seen.add(List.of(source.identityKind(), source.externalId()))
                    || source.displayName()!=null && source.displayName().length()>65536
                    || !Set.of("displayName","phone","avatar","visitCount","totalSpent","averageCheck","statisticsCurrency").containsAll(source.presentFields())
                    || !Set.of("phone").containsAll(source.contacts().keySet())
                    || source.contacts().values().stream().anyMatch(value -> value.length()>256)) invalid();
            var rows=store.em.createQuery("from CrmClientEntity where project.id=:project and identityKind=:kind and externalId=:external", CrmClientEntity.class)
                    .setParameter("project",project.getId()).setParameter("kind",source.identityKind()).setParameter("external",source.externalId()).getResultList();
            boolean created=rows.isEmpty();
            var row=created ? new CrmClientEntity() : rows.getFirst();
            if (created) { row.setProject(project); row.setIdentityKind(source.identityKind()); row.setExternalId(source.externalId()); }
            // Preserve the legacy statistics timestamp before a directory-only update changes lastObservedAt.
            if (row.getStatisticsUpdatedAt()==null && row.getAverageCheck()!=null)
                row.setStatisticsUpdatedAt(row.getLastObservedAt());
            if (source.visitCount()!=null && (source.visitCount()<0 || source.visitCount()>999999999L)
                    || source.totalSpent()!=null && (source.totalSpent().signum()<0 || source.totalSpent().scale()>2
                    || source.totalSpent().precision()-source.totalSpent().scale()>17)) invalid();
            if (source.presentFields().contains("visitCount")) row.setVisitCount(source.visitCount());
            if (source.presentFields().contains("totalSpent")) row.setTotalSpent(source.totalSpent());
            if (source.averageCheck()!=null && (source.averageCheck().signum()<0 || source.averageCheck().scale()>2
                    || source.averageCheck().precision()-source.averageCheck().scale()>17)
                    || source.statisticsCurrency()!=null && !source.statisticsCurrency().matches("[A-Z]{3}")) invalid();
            if (source.presentFields().contains("averageCheck")) row.setAverageCheck(source.averageCheck());
            if (source.presentFields().contains("statisticsCurrency")) row.setStatisticsCurrency(source.statisticsCurrency());
            if (source.presentFields().containsAll(Set.of("visitCount","totalSpent","averageCheck","statisticsCurrency"))
                    && source.visitCount()!=null && source.averageCheck()!=null && source.totalSpent()!=null && source.statisticsCurrency()!=null)
            { row.setStatisticsUpdatedAt(page.observedAt()); row.setStatisticsRetryAt(null); }
            if (source.presentFields().contains("displayName")) row.setDisplayName(source.displayName());
            if (source.presentFields().contains("phone")) {
                if (!source.contacts().containsKey("phone")) invalid();
                row.setContacts(new LinkedHashMap<>(source.contacts()));
                row.setNormalizedPhone(CrmPhone.normalize(source.contacts().get("phone")));
            }
            // Only reviewed profile fields are persisted; never raw provider HTML or arbitrary JSON.
            if ("R08".equals(source.profile().get("source"))) row.setProfile(Map.of("source","R08"));
            // Аватар — украшение: чужая ссылка не проходит проверку формы, а без скачанного файла
            // строка остаётся с прежним зеркалом (синхронизацию из-за картинки не ломаем).
            var canonical = CrmAvatarUrl.canonical(source.avatarUrl());
            var mirrored = canonical == null ? null : avatarKeys.get(source.externalId());
            if (mirrored != null) { row.setAvatarOrigin(CrmAvatarUrl.key(canonical)); row.setAvatarUrl(mirrored); }
            else if (canonical == null && source.avatarUrl() == null && source.presentFields().contains("avatar")) {
                // Провайдер сказал, что фото нет: зеркало снимается, иначе карточка вечно держала бы
                // файл человека, которого у провайдера уже нет. Сам объект не удаляем: ключи в хосте
                // разделяются между таблицами, их собирает 13.5 по числу ссылок.
                row.setAvatarOrigin(null); row.setAvatarUrl(null);
            }
            row.setLastObservedAt(page.observedAt());
            if (created) store.em.persist(row);
        }
        store.em.flush();
    }
    private static void invalid() { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}
