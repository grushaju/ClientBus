package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.dikidi.DikidiTechnicalCredentials;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.server.crm.CrmSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.UUID;

/**
 * 13.4; 13.5 — выдача учётных данных по аккаунту канала. Привязка больше не
 * конфигурационная: подключение резолвится по цепочке channelaccount → channel →
 * workspace → активный (неархивный) DIKIDI-проект. Секрет не копируется: каждый
 * вызов читает строку подключения и расшифровывает её существующим шифром (те же
 * credentials/AAD, что у CRM), а credentialVersion из строки гарантирует, что
 * после смены доступа чат получит новую сессию.
 */
@Component
public class DikidiCredentialsHost implements DikidiTechnicalCredentials {
    private final JdbcTemplate jdbc;
    private final CrmSecretCipher cipher;
    public DikidiCredentialsHost(JdbcTemplate jdbc, CrmSecretCipher cipher) {
        this.jdbc = jdbc; this.cipher = cipher;
    }
    @Override public DikidiCredential obtain(UUID channelAccountId) {
        // Пустая строка — IllegalStateException от jdbc: у канала обязано быть активное DIKIDI-подключение.
        var row = jdbc.queryForMap("""
                select c.id,c.organizationid,c.credentialversion,c.login,c.secretkeyid,c.secretciphertext
                from crm.connection c
                join crm.project p on p.connectionid = c.id
                join dbo.channel ch on ch.workspaceid = p.workspaceid
                join dbo.channelaccount ca on ca.channelid = ch.channelid
                where ca.channelaccountid = ? and ch.type = 'DIKIDI'
                  and p.provider = 'DIKIDI' and p.archived = false
                  and p.externalid = ca.externalid
                """, channelAccountId);
        UUID connection = UUID.fromString(String.valueOf(row.get("id")));
        char[] secret = cipher.decrypt(UUID.fromString(String.valueOf(row.get("organizationid"))),
                connection, (String) row.get("secretkeyid"), (byte[]) row.get("secretciphertext"));
        try { // ревю 13.4: входной массив обнуляется сразу, как в CrmCredentials.open
            return new DikidiCredential(connection, ((Number) row.get("credentialversion")).longValue(),
                    (String) row.get("login"), secret);
        } finally { Arrays.fill(secret, '\0'); }
    }
}
