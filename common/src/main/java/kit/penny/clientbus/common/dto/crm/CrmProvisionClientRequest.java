package kit.penny.clientbus.common.dto.crm;

import java.util.UUID;

public record CrmProvisionClientRequest(UUID clientId, Long contextVersion, String firstName, String lastName,
                                       String phone, boolean differentPerson) {}
