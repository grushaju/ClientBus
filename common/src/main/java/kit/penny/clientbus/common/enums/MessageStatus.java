package kit.penny.clientbus.common.enums;

public enum MessageStatus {
    RECEIVED (1),
    QUEUED (2),
    SENDING (3),
    SENT (4),
    DELIVERED (5),
    READ (6),
    FAILED (7);

    private final int code;

    MessageStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}