package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.server.persistence.entity.crm.CrmConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CrmConnectionRepository extends JpaRepository<CrmConnectionEntity, UUID> {
    boolean existsByOrganizationIdAndProviderAndLogin(UUID organizationId, kit.penny.clientbus.common.enums.CrmType provider, String login);
    boolean existsByOrganizationIdAndProviderAndLoginAndIdNot(UUID organizationId, kit.penny.clientbus.common.enums.CrmType provider, String login, UUID id);
    List<CrmConnectionEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
    Optional<CrmConnectionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
