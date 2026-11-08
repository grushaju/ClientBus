package kit.penny.clientbus.common.dto.crm;

/** A proven project-client reference from saved visits, not an imported CRM profile. */
public record CrmAppointmentClientDto(String id, String displayName, String phone) {}
