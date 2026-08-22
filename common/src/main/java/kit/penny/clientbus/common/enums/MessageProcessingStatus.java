package kit.penny.clientbus.common.enums;

public enum MessageProcessingStatus {

    /**
     * Сообщение получено ClientBus,
     * но ещё не начало обрабатываться.
     */
    RECEIVED,

    /**
     * Сообщение находится в процессе обработки.
     */
    PROCESSING,

    /**
     * Сообщение успешно обработано.
     */
    PROCESSED,

    /**
     * Обработка завершилась ошибкой.
     */
    FAILED
}