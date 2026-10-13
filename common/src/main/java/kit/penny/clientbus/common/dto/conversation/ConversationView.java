package kit.penny.clientbus.common.dto.conversation;

/**
 * Представления списка диалогов Inbox.
 */
public enum ConversationView {

    /**
     * Диалоги, назначенные текущему сотруднику.
     */
    MINE,

    /**
     * Диалоги без назначенного сотрудника.
     */
    UNASSIGNED,

    /**
     * Диалоги из личной группы текущего сотрудника.
     */
    GROUP
}