package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrganizationRepository
        extends JpaRepository<OrganizationEntity, UUID> {

    boolean existsByNameIgnoreCase(String name);
}