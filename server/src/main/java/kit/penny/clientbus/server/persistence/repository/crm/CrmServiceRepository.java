package kit.penny.clientbus.server.persistence.repository.crm;
import kit.penny.clientbus.server.persistence.entity.crm.CrmServiceEntity;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface CrmServiceRepository extends JpaRepository<CrmServiceEntity, UUID>, JpaSpecificationExecutor<CrmServiceEntity> {
}
