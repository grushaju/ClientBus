package kit.penny.clientbus.common.dto.clientaccount;

public record UpdateClientAccountRequest(

        String username,

        String phone,

        String displayName
) {
}