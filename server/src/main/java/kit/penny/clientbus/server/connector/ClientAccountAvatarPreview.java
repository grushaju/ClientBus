package kit.penny.clientbus.server.connector;

/** Small, temporary image for the active discovery dialog only. */
public record ClientAccountAvatarPreview(String contentType, String base64) {
    @Override
    public String toString() {
        return "ClientAccountAvatarPreview[contentType=" + contentType + ", base64=<redacted>]";
    }
}
