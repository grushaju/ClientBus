package kit.penny.clientbus.common.dto.conversation;

/**
 * Фильтр по наличию связи ClientAccount -> Client.
 */
public enum ConversationClientLinkFilter {

    /**
     * Не фильтровать по связи с Client.
     */
    ANY,

    /**
     * Только аккаунты, связанные с Client.
     */
    WITH_CLIENT,

    /**
     * Только аккаунты без связанного Client.
     */
    WITHOUT_CLIENT
}