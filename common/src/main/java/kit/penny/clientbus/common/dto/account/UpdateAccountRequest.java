package kit.penny.clientbus.common.dto.account;

public record UpdateAccountRequest(

        String username,

        String phone,

        String displayName
) {
}