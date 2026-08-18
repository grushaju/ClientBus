package kit.penny.clientbus.common.enums;

public enum ChannelType {
    WHATSAPP (1),
    TELEGRAM (2),
    MAX (3),
    VK(4),
    AVITO(5),
    DIKIDI(6),
    WHATSAPP_BUSINESS(11),
    TELEGRAM_BOT (22),
    MAX_BOT(23),
    VK_BOT(24);

    private final int code;

    ChannelType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
