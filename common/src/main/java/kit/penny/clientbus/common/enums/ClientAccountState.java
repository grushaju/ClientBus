package kit.penny.clientbus.common.enums;

public enum ClientAccountState {

    ACTIVE,

    /**
     * Клиент больше не интересен,
     * но его история сохраняется.
     */
    ARCHIVE,

    /**
     * Это вообще не клиент.
     *
     * Например, знакомый написал на рабочий аккаунт.
     */
    IGNORED,

    /**
     * Не принимать сообщения от аккаунта
     * на уровне платформы мессенджера.
     */
    BLOCKED
}