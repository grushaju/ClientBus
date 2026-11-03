package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.server.persistence.entity.crm.CrmMasterServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.*;

public interface CrmMasterServiceRepository extends JpaRepository<CrmMasterServiceEntity, UUID> {
    @EntityGraph(attributePaths = "master")
    List<CrmMasterServiceEntity> findAllByProjectIdAndServiceIdInOrderByIdAsc(UUID projectId, Collection<UUID> serviceIds);
}
