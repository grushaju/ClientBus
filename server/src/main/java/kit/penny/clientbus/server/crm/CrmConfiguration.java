package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.dikidi.DikidiProvider;
import org.springframework.context.annotation.*;

@Configuration
public class CrmConfiguration {
    @Bean(destroyMethod = "close")
    public DikidiProvider dikidiProvider(CrmSettings settings) {
        return new DikidiProvider(new DikidiProvider.Settings(settings.connectTimeout(), settings.requestTimeout(), settings.maxResponseBytes()));
    }
}

