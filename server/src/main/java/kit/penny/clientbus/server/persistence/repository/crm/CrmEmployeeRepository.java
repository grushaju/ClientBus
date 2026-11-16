package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.server.persistence.entity.crm.CrmEmployeeEntity;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.*;

public interface CrmEmployeeRepository extends JpaRepository<CrmEmployeeEntity, UUID>, JpaSpecificationExecutor<CrmEmployeeEntity> {
    Page<CrmEmployeeEntity> findAllByProjectId(UUID projectId, Pageable page);
    Slice<CrmEmployeeEntity> findAllByProjectIdAndExternalIdGreaterThan(UUID projectId, String externalId, Pageable page);
    Optional<CrmEmployeeEntity> findByProjectIdAndExternalId(UUID projectId, String externalId);
    List<CrmEmployeeEntity> findAllByEmployeeId(UUID employeeId);
    boolean existsByMappingUpdatedById(UUID employeeId);
}
