package kit.penny.clientbus.server;

import kit.penny.clientbus.server.connector.telegram.TelegramClientConfiguration;
import kit.penny.tdlib.TdlibAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@ConfigurationPropertiesScan
@SpringBootApplication(
        exclude = {
                UserDetailsServiceAutoConfiguration.class,
                TdlibAutoConfiguration.class
        }
)
@ComponentScan(
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = TelegramClientConfiguration.class
                )
        }
)
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

}
