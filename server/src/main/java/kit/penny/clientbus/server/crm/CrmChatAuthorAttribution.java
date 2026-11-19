package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.crm.CrmEmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.crm.CrmProjectRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Атрибуция автора сообщения, пришедшего со стороны внешнего канала (13.5, живая
 * приёмка DIKIDI; таблица crm.employee с 16.11). Автор исходящего DIKIDI — это
 * пользователь платформы (masters.info отдаёт строчки item_type=master под их user id,
 * а доступы проекта — другие пользователи под своими user id), поэтому внешний id автора
 * совпадает с crm.employee.externalid активного проекта, а сотрудник берётся из
 * маппинга crm.employee.employeeid. Роль не важна: мастер это или не-мастер
 * (админ/ресепшн — живой пример: сообщения администратора проекта без карточки мастера).
 * Нет строки сотрудника (например, суперадмин-владелец) или маппинг не выполнен —
 * NULL; подстановка по имени/роли запрещена (CRM_SEMANTICS §6).
 */
@Component
public class CrmChatAuthorAttribution {

    private final CrmProjectRepository projects;
    private final CrmEmployeeRepository employees;

    public CrmChatAuthorAttribution(CrmProjectRepository projects, CrmEmployeeRepository employees) {
        this.projects = projects;
        this.employees = employees;
    }

    public UUID employeeId(ChannelAccountEntity account, String authorExternalId) {
        if (account == null || authorExternalId == null || authorExternalId.isBlank()) return null;
        // Резолвинг осмыслен только для DIKIDI: внешние id авторов других каналов
        // не имеют отношения к crm.employee проекта (ресью N1 15.11, defense-in-depth —
        // ни один коннектор кроме DIKIDI автора не заполняет).
        if (account.getChannel() == null || account.getChannel().getType()
                != kit.penny.clientbus.common.enums.ChannelType.DIKIDI) return null;
        var workspace = account.getChannel().getWorkspace();
        if (workspace == null) return null;
        return projects
                .findFirstByWorkspaceIdAndProviderAndArchivedFalseOrderByCreatedAtAsc(
                        workspace.getId(), CrmType.DIKIDI)
                .filter(project -> project.getExternalId().equals(account.getExternalId()))
                .flatMap(project -> employees.findByProjectIdAndExternalId(project.getId(), authorExternalId))
                // Связь 13.6 («учётка ↔ сотрудник каталога», подтверждается человеком):
                // строка учётки наследует маппинг цели; цепочки связей запрещены сервисом,
                // здесь — ровно один переход.
                .flatMap(row -> {
                    if (row.getEmployee() != null) return java.util.Optional.of(row);
                    var linked = row.getLinkedEmployee();
                    return linked == null || linked.getEmployee() == null
                            ? java.util.Optional.<kit.penny.clientbus.server.persistence.entity.crm.CrmEmployeeEntity>empty()
                            : java.util.Optional.of(linked);
                })
                .map(row -> row.getEmployee() == null ? null : row.getEmployee().getId())
                .orElse(null);
    }
}
