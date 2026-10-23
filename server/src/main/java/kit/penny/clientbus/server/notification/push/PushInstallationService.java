package kit.penny.clientbus.server.notification.push;

import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.InstallationAccessPolicy;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class PushInstallationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final InstallationAccessPolicy policy;
    private final PushEndpointCipher cipher;
    private final PushSettings settings;
    private final NotificationProperties config;
    private final PushDeliveryStore deliveries;
    public PushInstallationService(NamedParameterJdbcTemplate jdbc, InstallationAccessPolicy policy,
            PushEndpointCipher cipher, PushSettings settings, NotificationProperties config, PushDeliveryStore deliveries) {
        this.jdbc=jdbc;this.policy=policy;this.cipher=cipher;this.settings=settings;this.config=config;this.deliveries=deliveries;
    }
    @Transactional
    public PushInstallationDto put(AccessSubject subject, UUID id, PushInstallationRequest request) {
        UUID organization = organization(subject);
        if (!settings.allows(request.appId(),request.provider(),request.environment(),request.platform()))
            throw new IllegalArgumentException("Invalid installation configuration");
        var parameters = new MapSqlParameterSource().addValue("id",id).addValue("owner",subject.employeeId())
                .addValue("organization",organization).addValue("platform",request.platform().name())
                .addValue("provider",request.provider().name()).addValue("app",request.appId())
                .addValue("environment",request.environment().name()).addValue("enabled",request.enabled())
                .addValue("permission",request.reportedPermission().name()).addValue("proof",hash(request.installationSecret()))
                .addValue("endpoint",cipher.encrypt(request.registrationReference()))
                .addValue("fingerprint",HexFormat.of().formatHex(hash(request.registrationReference())))
                .addValue("activity",config.installationActivity().toMillis()).addValue("version",request.expectedRegistrationVersion());
        if (request.expectedRegistrationVersion()==0) {
            int inserted=jdbc.update("""
                insert into dbo.pushinstallation
                (id,organizationid,recipientemployeeid,registrationversion,bindingversion,endpointversion,
                 platform,provider,appid,environment,encryptedendpoint,endpointfingerprint,activeendpointfingerprint,
                 ownershipsecrethash,enabled,reportedpermission,lastseenat,activeuntil)
                values (:id,:organization,:owner,1,1,1,:platform,:provider,:app,:environment,:endpoint,:fingerprint,
                 case when :enabled then :fingerprint else null end,:proof,:enabled,:permission,clock_timestamp(),
                 clock_timestamp()+(:activity * interval '1 millisecond')) on conflict (id) do nothing
                """,parameters);
            if (inserted==0) policy.requireMutation(subject,id,request.installationSecret(),0,InstallationAccessPolicy.Mutation.CREATE);
        } else {
            // A PUT with the new authenticated identity and the current proof/version is an explicit rebind.
            policy.requireMutation(subject,id,request.installationSecret(),request.expectedRegistrationVersion(),InstallationAccessPolicy.Mutation.REBIND);
            int updated=jdbc.update("""
                update dbo.pushinstallation set registrationversion=registrationversion+1,
                bindingversion=bindingversion+case when recipientemployeeid<>:owner or organizationid<>:organization then 1 else 0 end,
                endpointversion=endpointversion+case when endpointfingerprint<>:fingerprint or provider<>:provider
                    or appid<>:app or environment<>:environment or platform<>:platform then 1 else 0 end,
                recipientemployeeid=:owner,organizationid=:organization,platform=:platform,provider=:provider,
                appid=:app,environment=:environment,encryptedendpoint=:endpoint,endpointfingerprint=:fingerprint,
                activeendpointfingerprint=case when :enabled then :fingerprint else null end,
                enabled=:enabled,reportedpermission=:permission,lastseenat=clock_timestamp(),
                activeuntil=clock_timestamp()+(:activity * interval '1 millisecond')
                where id=:id and registrationversion=:version
                """,parameters);
            if(updated!=1) throw conflict(id);
            deliveries.cancelObsolete(id);
        }
        return dto(id);
    }
    @Transactional
    public void delete(AccessSubject subject,UUID id,String proof,long version) {
        policy.requireMutation(subject,id,proof,version,InstallationAccessPolicy.Mutation.DELETE);
        if(jdbc.update("""
            update dbo.pushinstallation set registrationversion=registrationversion+1,enabled=false,activeendpointfingerprint=null
            where id=:id and registrationversion=:version
            """,Map.of("id",id,"version",version))!=1) throw conflict(id);
        deliveries.cancelObsolete(id);
    }
    private UUID organization(AccessSubject subject) {
        if(subject.expired(Instant.now())) throw new AccessDeniedException("Access denied");
        return jdbc.query("""
            select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid
            where e.employeeid=:employee and u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')
            """,Map.of("employee",subject.employeeId()),(rs,row)->rs.getObject(1,UUID.class)).stream().findFirst()
                .orElseThrow(()->new AccessDeniedException("Access denied"));
    }
    private InstallationAccessPolicy.VersionConflict conflict(UUID id) {
        Long version=jdbc.queryForObject("select registrationversion from dbo.pushinstallation where id=:id",Map.of("id",id),Long.class);
        return new InstallationAccessPolicy.VersionConflict(Objects.requireNonNull(version));
    }
    private PushInstallationDto dto(UUID id) {
        return jdbc.queryForObject("select * from dbo.pushinstallation where id=:id",Map.of("id",id),(rs,row)->
            new PushInstallationDto(id,rs.getLong("registrationversion"),rs.getLong("bindingversion"),rs.getLong("endpointversion"),
                rs.getBoolean("enabled"),kit.penny.clientbus.common.notification.PushPermission.valueOf(rs.getString("reportedpermission")),
                rs.getTimestamp("lastseenat").toInstant()));
    }
    private static byte[] hash(String value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
