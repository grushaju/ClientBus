package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository
        extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findAllByClientId(
            UUID clientId
    );

    List<AccountEntity> findAllByClientIdAndChannelType(
            UUID clientId,
            ChannelType channelType
    );

    Optional<AccountEntity> findByClientIdAndChannelTypeAndExternalId(
            UUID clientId,
            ChannelType channelType,
            String externalId
    );

    @Query("""
        SELECT a
        FROM AccountEntity a
        WHERE a.client.id = :clientId
          AND (
               LOWER(a.username) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.phone) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.externalId) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.displayName) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<AccountEntity> searchByClient(
            @Param("clientId") UUID clientId,
            @Param("query") String query
    );

    boolean existsByClientIdAndChannelTypeAndExternalId(
            UUID clientId,
            ChannelType channelType,
            String externalId
    );
}