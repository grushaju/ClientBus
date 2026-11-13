package kit.penny.clientbus.server.security.jwt;

import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** TEMP-12-7-DIAGNOSTICS: in-context dispatch probe, delete after use. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.sse-enabled=false", "clientbus.notifications.cleanup-poll-ms=3600000"})
class JwtServiceDispatchIT extends AbstractIntegrationTest {

    @Autowired JwtService jwt;

    @Test void directBeanCall() throws Exception {
        try (var w = new FileWriter("D:/Projects/Java/ClientBus/temp127-it.txt", true)) {
            w.write("autowired class=" + jwt.getClass().getName() + " loader=" + jwt.getClass().getClassLoader()
                    + " id=" + System.identityHashCode(jwt) + "\n");
            try {
                var r = jwt.isTokenValid("abc");
                w.write("isTokenValid returned " + r + "\n");
            } catch (Throwable t) {
                w.write("isTokenValid threw " + t + "\n");
            }
        }
        assertNotNull(jwt);
    }
}
