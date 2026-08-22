package kit.penny.clientbus.common.enums;

public enum MessageDeliveryStatus {

    /**
     * Исходящее сообщение создано,
     * но ещё не отправлено Connector'ом.
     */
    PENDING,

    /**
     * Сообщение передано внешней платформе.
     */
    SENT,

    /**
     * Внешняя платформа подтвердила доставку.
     */
    DELIVERED,

    /**
     * Внешняя платформа подтвердила прочтение.
     */
    READ,

    /**
     * Отправка/доставка завершилась ошибкой.
     */
    FAILED
}