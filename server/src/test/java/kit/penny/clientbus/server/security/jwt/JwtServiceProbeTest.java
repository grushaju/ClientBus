package kit.penny.clientbus.server.security.jwt;

import kit.penny.clientbus.server.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/** TEMP-12-7-DIAGNOSTICS: bare dispatch probe, delete after use. */
class JwtServiceProbeTest {

    @Test void plainDispatch() {
        var service = new JwtService("FnQHtz4yCzaBgma1a25xV30mShdAQZx/33msarZ57f0=", 1800000);
        var user = new UserEntity();
        try {
            java.lang.reflect.Method setId = UserEntity.class.getMethod("setId", UUID.class);
            setId.invoke(user, UUID.randomUUID());
            java.lang.reflect.Method setUsername = UserEntity.class.getMethod("setUsername", String.class);
            setUsername.invoke(user, "probe");
        } catch (Exception ignored) {}
        String token = service.generateToken(user);
        System.err.println("TEMP127 probe token=" + (token == null ? "null" : token.substring(0, Math.min(20, token.length()))));
        boolean valid = service.isTokenValid(token);
        System.err.println("TEMP127 probe returned " + valid);
    }
}
