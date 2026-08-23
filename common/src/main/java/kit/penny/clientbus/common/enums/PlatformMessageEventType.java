package kit.penny.clientbus.common.enums;

public enum PlatformMessageEventType {

    /**
     * Платформа подтвердила отправку сообщения.
     */
    SENT,

    /**
     * Платформа подтвердила доставку сообщения.
     */
    DELIVERED,

    /**
     * Платформа подтвердила прочтение сообщения.
     */
    READ,

    /**
     * Платформа сообщила об ошибке доставки.
     */
    FAILED

}