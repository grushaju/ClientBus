package kit.penny.clientbus.crm.api;

import java.util.Map;

/**
 * Запись доступа проекта (страница permissions + ajax-форма edit/{accessId}, наблюдения
 * 16.11.2026): external id — id записи доступа (ключ строк страницы и URL формы, НЕ user id
 * платформы), masterExternalId — привязка «выбранный сотрудник» из select формы (nullable:
 * 0/пусто — учётка без сотрудника каталога), phone — скрытое поле number формы (E.164),
 * attributes — roleLabel и иные наблюдаемые поля. Мост «запись доступа ↔ user id» в ответах
 * платформы не наблюдался: связь с строкой штата устанавливает человек, запись притягивается
 * к карточке по доказанному masterExternalId.
 */
public record CrmStaffAccess(String externalId, String displayName, String masterExternalId, String phone,
        boolean mainOwner, Map<String, String> attributes) {
    public CrmStaffAccess { attributes = Map.copyOf(attributes); }
}
