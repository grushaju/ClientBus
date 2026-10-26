package kit.penny.clientbus.server.security.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "clientbus.notifications.sse-enabled=false","clientbus.notifications.history-consumer-enabled=false",
        "clientbus.notifications.metrics-poll-ms=60000","clientbus.notifications.cleanup-poll-ms=3600000",
        "clientbus.notifications.history-worker-poll-ms=3600000","clientbus.notifications.history-relay-poll-ms=3600000"})
abstract class ManagementAuthenticationIntegrationSupport extends AbstractIntegrationTest {
    private static final String USERNAME="management-acceptance";
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    @DynamicPropertySource static void credentials(DynamicPropertyRegistry properties) {
        properties.add("clientbus.management.username",()->USERNAME);
        properties.add("clientbus.management.password-hash",()->HASH);
    }
    @LocalServerPort int port;
    @Value("${server.servlet.context-path:}") String contextPath;
    @Value("${management.endpoints.web.base-path:/actuator}") String managementPath;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean(name="dataSource") HikariDataSource dataSource;
    UUID adminId;
    String adminToken,employeeToken,disabledToken;
    @BeforeEach void identities() {
        tx.executeWithoutResult(status->{
            var admin=user(UserRole.SUPER_ADMIN,true);adminId=admin.getId();adminToken=jwt.generateToken(admin);
            employeeToken=jwt.generateToken(user(UserRole.EMPLOYEE,true));
            disabledToken=jwt.generateToken(user(UserRole.SUPER_ADMIN,false));
        });
    }
    private UserEntity user(UserRole role,boolean enabled) {
        String key=UUID.randomUUID().toString();
        var user=new UserEntity(key,key+"@management.test","unused",role);user.setEnabled(enabled);
        em.persist(user);em.flush();return user;
    }
    String management(String suffix) {return (managementPath.equals("/")?"":managementPath)+suffix;}
    String basic() {return basic(USERNAME,PASSWORD);}
    String wrongBasic() {return basic(USERNAME,"wrong");}
    private static String basic(String username,String password) {
        return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
    }
    HttpResponse<String> get(HttpClient client,String path,String authorization) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+contextPath+path)).timeout(Duration.ofSeconds(10));
        if(authorization!=null) request.header("Authorization",authorization);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
