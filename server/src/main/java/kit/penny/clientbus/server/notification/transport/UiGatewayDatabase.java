package kit.penny.clientbus.server.notification.transport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;

/** Small independent read pool: stalled ACL checks cannot exhaust the business pool. */
@Component
@ConditionalOnProperty(prefix="clientbus.notifications", name="sse-enabled", havingValue="true")
public class UiGatewayDatabase {
    private final HikariDataSource pool;
    final NamedParameterJdbcTemplate jdbc;
    final AccessPolicy access;
    final NotificationRoutingPolicy routing;

    public UiGatewayDatabase(DataSource business, UiGatewayProperties config) {
        if (!(business instanceof HikariDataSource source) || source.getJdbcUrl()==null)
            throw new IllegalArgumentException("UI gateway requires the PostgreSQL Hikari JDBC datasource");
        var settings=new HikariConfig();
        settings.setJdbcUrl(boundedUrl(source.getJdbcUrl()));
        settings.setUsername(source.getUsername()); settings.setPassword(source.getPassword());
        var properties=new Properties(); properties.putAll(source.getDataSourceProperties());
        settings.setDataSourceProperties(properties);
        settings.setDriverClassName(source.getDriverClassName());
        settings.setSchema(source.getSchema()); settings.setCatalog(source.getCatalog());
        settings.setConnectionInitSql(source.getConnectionInitSql());
        settings.setPoolName("ui-acl"); settings.setMaximumPoolSize(config.workers()); settings.setMinimumIdle(0);
        settings.setConnectionTimeout(2000); settings.setValidationTimeout(1000);
        settings.setInitializationFailTimeout(-1); settings.setReadOnly(true);
        pool=new HikariDataSource(settings);
        var template=new JdbcTemplate(pool); template.setQueryTimeout(2);
        jdbc=new NamedParameterJdbcTemplate(template);
        access=new AccessPolicy(jdbc); routing=new NotificationRoutingPolicy(access);
    }

    private static String boundedUrl(String url) {
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("UI gateway requires PostgreSQL");
        var parts=url.split("\\?",2);
        var query=new ArrayList<String>();
        var overridden=Set.of("socketTimeout","connectTimeout","cancelSignalTimeout");
        if (parts.length==2) for (String parameter:parts[1].split("&")) {
            if (!parameter.isEmpty() && !overridden.contains(parameter.split("=",2)[0])) query.add(parameter);
        }
        query.add("socketTimeout=3"); query.add("connectTimeout=2"); query.add("cancelSignalTimeout=1");
        return parts[0]+"?"+String.join("&",query);
    }

    @PreDestroy public void close() { pool.close(); }
}
