package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.crm.CrmMasterRepository;
import kit.penny.clientbus.server.persistence.repository.crm.CrmProjectRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Атрибуция автора сообщения, пришедшего со стороны внешнего канала (13.5, живая
 * приёмка DIKIDI). Никаких новых связей: автор исходящего DIKIDI — это пользователь
 * платформы, а мастер DIKIDI и есть пользователь (masters.info отдаёт строчки
 * item_type=master под их user id), поэтому внешний id автора совпадает с
 * crm.master.externalid активного проекта, а сотрудник берётся из существующего
 * маппинга crm.master.employeeid.
 * Нет строки мастера (суперадмин-владелец, администратор салона) или маппинг не
 * выполнен — NULL; подстановка по имени/роли запрещена (CRM_SEMANTICS §6).
 */
@Component
public class CrmChatAuthorAttribution {

    private final CrmProjectRepository projects;
    private final CrmMasterRepository masters;

    public CrmChatAuthorAttribution(CrmProjectRepository projects, CrmMasterRepository masters) {
        this.projects = projects;
        this.masters = masters;
    }

    public UUID employeeId(ChannelAccountEntity account, String authorExternalId) {
        if (account == null || authorExternalId == null || authorExternalId.isBlank()) return null;
        // Резолвинг осмыслен только для DIKIDI: внешние id авторов других каналов
        // не имеют отношения к crm.master проекта (ресью N1 15.11, defense-in-depth —
        // ни один коннектор кроме DIKIDI автора не заполняет).
        if (account.getChannel() == null || account.getChannel().getType()
                != kit.penny.clientbus.common.enums.ChannelType.DIKIDI) return null;
        var workspace = account.getChannel().getWorkspace();
        if (workspace == null) return null;
        return projects
                .findFirstByWorkspaceIdAndProviderAndArchivedFalseOrderByCreatedAtAsc(
                        workspace.getId(), CrmType.DIKIDI)
                .flatMap(project -> masters.findByProjectIdAndExternalId(project.getId(), authorExternalId))
                .map(master -> master.getEmployee() == null ? null : master.getEmployee().getId())
                .orElse(null);
    }
}
