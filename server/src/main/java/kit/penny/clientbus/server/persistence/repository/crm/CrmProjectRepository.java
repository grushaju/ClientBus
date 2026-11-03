package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CrmProjectRepository extends JpaRepository<CrmProjectEntity, UUID> {
    List<CrmProjectEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
    List<CrmProjectEntity> findAllByWorkspaceIdAndOrganizationIdOrderByCreatedAtAsc(UUID workspaceId, UUID organizationId);
    Optional<CrmProjectEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<CrmProjectEntity> findByOrganizationIdAndProviderAndExternalId(UUID organizationId, CrmType provider, String externalId);
    boolean existsByWorkspaceId(UUID workspaceId);
    boolean existsByWorkspaceIdAndProvider(UUID workspaceId, CrmType provider);
    List<CrmProjectEntity> findAllByConnectionId(UUID connectionId);
}
