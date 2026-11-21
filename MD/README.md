# Документация ClientBus

Основные правила работы — [CONSTITUTION.MD](../CONSTITUTION.MD). Входная точка проекта — [README](../README.md).

| Раздел | Содержание и основные документы |
| --- | --- |
| [CRM](CRM/) | [Контракты](CRM/CRM_CONTRACTS.md), [настройка](CRM/CRM_SETUP.md), [синхронизация](CRM/CRM_SYNC.md), [клиенты и создание](CRM/CRM_CLIENT_CREATION.md), [протокол DIKIDI](CRM/CRM_DIKIDI_PROTOCOL.md) |
| [INBOX](INBOX/) | [Права](INBOX/INBOX_ACCESS.md), [сообщения](INBOX/INBOX_MESSAGES.md), [детали клиента](INBOX/INBOX_CLIENT_DETAILS.md), [приёмка](INBOX/INBOX_ACCEPTANCE.md) |
| [NOTIFICATION](NOTIFICATION/) | [Контракты](NOTIFICATION/NOTIFICATION_CONTRACTS.md), [доступ](NOTIFICATION/NOTIFICATION_ACCESS.md), [эксплуатация](NOTIFICATION/NOTIFICATION_OPERATIONS.md), [приёмка](NOTIFICATION/NOTIFICATION_ACCEPTANCE.md) |
| [CHANNELS](CHANNELS/) | [Исходящие контакты и аватары](CHANNELS/OUTBOUND_CONTACT_AVATARS.md) |
| [MOBILE](MOBILE/) | [Интеграция мобильных push](MOBILE/MOBILE_PUSH_INTEGRATION.md) |
| [UI](UI/) | [Спецификация Inbox](<UI/10. inbox_ui_spec.md>), [эргономическое ревью CRM](UI/12-crm-ergonomics-review.md) |
| [STAGE](STAGE/) | Исторические планы этапов; [этап 13](<STAGE/Stage 13.txt>) закрыт 21.11.2026 |
| [ARCHITECTURE](ARCHITECTURE/) | [Автоматически сформированная структура исходников](ARCHITECTURE/index.md); не заменяет текущий код и контракты |
| [Бэклог](Backlog.md) | Отложенные функциональные доработки |

Сопроводительные инструкции запуска тестов остаются рядом с инструментами: [UI E2E](../ui/e2e/README.md). HTML-макеты хранятся в [ui/mockups](../ui/mockups/).

Структуру исходников обновляет `scripts/generate-project-structure.ps1`; результат записывается в `MD/ARCHITECTURE/index.md`. Пути исходников внутри него отсчитываются от корня репозитория. Дата генерации указана в самом файле.
