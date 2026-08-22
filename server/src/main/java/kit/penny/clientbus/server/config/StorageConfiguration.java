package kit.penny.clientbus.server.config;

import kit.penny.clientbus.server.config.properties.StorageProperties;
import kit.penny.clientbus.server.storage.AttachmentStorage;
import kit.penny.clientbus.server.storage.LocalAttachmentStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    public AttachmentStorage attachmentStorage(
            StorageProperties properties
    ) {
        return new LocalAttachmentStorage(
                properties.getLocalPath()
        );
    }
}
