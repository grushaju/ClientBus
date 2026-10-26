package kit.penny.clientbus.server.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Optional operator identity, independent of the business DB and confined to management endpoints. */
@ConfigurationProperties("clientbus.management")
public record ManagementAccessProperties(@DefaultValue("") String username,@DefaultValue("") String passwordHash) {
    public ManagementAccessProperties {
        username=username==null?"":username;
        passwordHash=passwordHash==null?"":passwordHash;
        boolean absent=username.isBlank() && passwordHash.isBlank();
        if(!absent && (username.isBlank() || username.length()>128 || username.contains(":") || !username.equals(username.strip())
                || username.chars().anyMatch(Character::isISOControl)
                || !passwordHash.matches("\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}")))
            throw new IllegalArgumentException("Management credentials require a username and a BCrypt hash with cost 4..16");
    }
    public boolean enabled() {return !username.isBlank();}
    @Override public String toString() {return "ManagementAccessProperties[enabled="+enabled()+", credentials=<redacted>]";}
}
