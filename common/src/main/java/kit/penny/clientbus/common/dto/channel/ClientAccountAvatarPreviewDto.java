package kit.penny.clientbus.common.dto.channel;

/** Short-lived image content for discovery; never a storage key or a write instruction. */
public record ClientAccountAvatarPreviewDto(String contentType, String base64) {
    @Override public String toString() {
        return "ClientAccountAvatarPreviewDto[contentType=" + contentType + ", content=<redacted>]";
    }
}
