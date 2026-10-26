package kit.penny.clientbus.server.connector;

/** A real public profile photo fetched by the connector, before attachment persistence. */
public record ClientAccountAvatar(byte[] content, String contentType, String externalPhotoId) {
    public ClientAccountAvatar {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public String toString() {
        return "ClientAccountAvatar[content=<redacted>, contentType=" + contentType + "]";
    }
}
