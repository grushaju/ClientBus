package kit.penny.clientbus.common.enums;

public enum MessageDirection {
    INBOUND (0),
    OUTBOUND (1);

    private final int code;

    MessageDirection(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
