package kit.penny.clientbus.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clientbus.storage")
public class StorageProperties {

    /**
     * Корневая директория локального Object Storage.
     */
    private String localPath;

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }
}
