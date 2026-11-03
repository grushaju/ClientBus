package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.server.persistence.entity.crm.CrmMasterEntity;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.*;

public interface CrmMasterRepository extends JpaRepository<CrmMasterEntity, UUID>, JpaSpecificationExecutor<CrmMasterEntity> {
    Page<CrmMasterEntity> findAllByProjectId(UUID projectId, Pageable page);
    Slice<CrmMasterEntity> findAllByProjectIdAndExternalIdGreaterThan(UUID projectId, String externalId, Pageable page);
    Optional<CrmMasterEntity> findByProjectIdAndExternalId(UUID projectId, String externalId);
    List<CrmMasterEntity> findAllByEmployeeId(UUID employeeId);
    boolean existsByMappingUpdatedById(UUID employeeId);
}
