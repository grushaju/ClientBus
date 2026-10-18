export const GROUP_ICON_OPTIONS = [
    { value: 'target.svg', label: 'Цель' },
    { value: 'fire.svg', label: 'Огонь' },
    { value: 'heart.svg', label: 'Сердце' },
    { value: 'ban.svg', label: 'Запрет' },
    { value: 'like.svg', label: 'Нравится' },
    { value: 'tag.svg', label: 'Метка' },
    { value: 'attach.svg', label: 'Вложение' },
    { value: 'pin.svg', label: 'Закрепить' },
    { value: 'book.svg', label: 'Книга' },
    { value: 'bookmark.svg', label: 'Закладка' },
    { value: 'cross.svg', label: 'Крестик' },
    { value: 'dollar.svg', label: 'Деньги' },
    { value: 'info.svg', label: 'Информация' },
    { value: 'lock.svg', label: 'Защита' },
    { value: 'mail.svg', label: 'Почта' },
    { value: 'mute.svg', label: 'Без звука' },
    { value: 'notifications.svg', label: 'Уведомления' },
    { value: 'restore.svg', label: 'Восстановить' },
    { value: 'speech.svg', label: 'Речь' },
    { value: 'tick.svg', label: 'Готово' },
    { value: 'trash.svg', label: 'Корзина' },
    { value: 'user.svg', label: 'Пользователь' },
    { value: 'warning.svg', label: 'Предупреждение' },
    { value: 'users.svg', label: 'Группа' },
] as const

export const DEFAULT_GROUP_ICON = 'users.svg'


export function resolveGroupIcon(icon: string | null | undefined): string {
    return GROUP_ICON_OPTIONS.some(option => option.value === icon) ? icon! : DEFAULT_GROUP_ICON
}
