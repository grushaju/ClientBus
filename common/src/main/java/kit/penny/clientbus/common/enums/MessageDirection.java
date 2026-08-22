package kit.penny.clientbus.common.enums;

public enum MessageDirection {

    /**
     * Сообщение пришло из внешнего канала
     * в ClientBus.
     */
    INBOUND,

    /**
     * Сообщение отправлено из ClientBus
     * во внешний канал.
     */
    OUTBOUND
}