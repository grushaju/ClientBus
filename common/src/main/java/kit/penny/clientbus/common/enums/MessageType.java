package kit.penny.clientbus.common.enums;

public enum MessageType {
    TEXT (1),
    IMAGE (2),
    VIDEO (3),
    AUDIO (4),
    DOCUMENT (5),
    STICKER (6),
    LOCATION (7),
    CONTACT (8),
    SYSTEM (9);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}