package kit.penny.clientbus.crm.api;

import java.util.Map;

/**
 * Держатель доступа проекта (company.accesses): пользователь платформы, который ведёт
 * или сопровождает работу кабинета (администратор, владелец) и может писать в чат.
 * External id — user id платформы (тот же ключ, что у авторов чата), роль — observation
 * (roles_id), бизнес-названия ролей DIKIDI не проверялись.
 */
public record CrmStaff(String externalId, String displayName, Map<String, String> attributes) {
    public CrmStaff { attributes = Map.copyOf(attributes); }
}
