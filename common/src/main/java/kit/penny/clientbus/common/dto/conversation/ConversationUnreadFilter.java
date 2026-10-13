package kit.penny.clientbus.common.dto.conversation;

/**
 * Фильтр по наличию непрочитанных сообщений.
 */
public enum ConversationUnreadFilter {

    /**
     * Не фильтровать по прочтению.
     */
    ANY,

    /**
     * Только диалоги с непрочитанными сообщениями.
     */
    HAS_UNREAD,

    /**
     * Только диалоги без непрочитанных сообщений.
     */
    READ_ONLY
}