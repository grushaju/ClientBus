package kit.penny.clientbus.server.config;

import kit.penny.clientbus.server.config.properties.StorageProperties;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.LocalIAttachmentStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    public IAttachmentStorage attachmentStorage(
            StorageProperties properties
    ) {
        return new LocalIAttachmentStorage(
                properties.getLocalPath()
        );
    }
}
