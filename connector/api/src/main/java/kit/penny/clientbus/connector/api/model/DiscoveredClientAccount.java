package kit.penny.clientbus.connector.api.model;

/** Authoritative connector lookup data. Discovery does not persist the account or its photo. */
public record DiscoveredClientAccount(
        String externalId,
        String username,
        String phone,
        String displayName,
        ClientAccountAvatarPreview avatarPreview
) {
}
