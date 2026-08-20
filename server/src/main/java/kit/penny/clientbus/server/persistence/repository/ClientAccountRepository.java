package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientAccountRepository
        extends JpaRepository<ClientAccountEntity, UUID> {

    List<ClientAccountEntity> findAllByClientId(
            UUID clientId
    );

    List<ClientAccountEntity> findAllByClientIdAndChannelType(
            UUID clientId,
            ChannelType channelType
    );

    Optional<ClientAccountEntity> findByClientIdAndChannelTypeAndExternalId(
            UUID clientId,
            ChannelType channelType,
            String externalId
    );

    List<ClientAccountEntity> findAllByClientIsNull();

    List<ClientAccountEntity> findAllByClientIsNullAndChannelType(
            ChannelType channelType
    );

    @Query("""
        SELECT a
        FROM ClientAccountEntity a
        WHERE a.client.id = :clientId
          AND (
               LOWER(a.username) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.phone) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.externalId) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.displayName) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<ClientAccountEntity> searchByClient(
            @Param("clientId") UUID clientId,
            @Param("query") String query
    );

    Optional<ClientAccountEntity> findByChannelTypeAndExternalId(
            ChannelType channelType,
            String externalId
    );

    boolean existsByChannelTypeAndExternalId(
            ChannelType channelType,
            String externalId
    );

    boolean existsByClientIdAndChannelTypeAndExternalId(
            UUID clientId,
            ChannelType channelType,
            String externalId
    );

}