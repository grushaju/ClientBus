# Stage 11.1 — Семантика уведомлений и каталог сценариев

Дата: 21.10.2026. Основание: [Stage 11.txt](<Stage 11.txt>), согласованный пользователем план выполнения 11.1.

Результат 11.1 принят пользователем 21.10.2026 («фиксируем. теперь пошли по пунктам»): аудит, матрица получателей, категории и семь решений последнего архитектурного ревью зафиксированы как основание реализации. Это целевое поведение, а не описание уже реализованной доставки. В 11.1 код приложения, схемы БД и API не изменялись. План 11.2 затем согласован; его контракты и JPA-модель описаны в [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md). По указанию пользователя новые таблицы создаёт Hibernate ddl-auto=update, новые миграции не нужны, коммиты не создавать.

Уточнение пользователя: Telegram — одна из реализаций [IChannelConnector](server/src/main/java/kit/penny/clientbus/server/connector/IChannelConnector.java), выбираемая общим [ChannelConnectorRegistry](server/src/main/java/kit/penny/clientbus/server/connector/ChannelConnectorRegistry.java). Notification работает с общими доменными ресурсами, командами и результатами коннекторов. Отдельных Telegram scope, прав, capabilities или owner-routing в этой подсистеме нет. Упоминания TDLib/auth ниже описывают найденные источники в текущей реализации; они не расширяют публичный контракт или общий интерфейс коннектора.

## 1. Границы и подтверждённое текущее состояние

| Область | Что подтверждено кодом | Следствие для Stage 11 |
|---|---|---|
| Роли | `UserRole`: EMPLOYEE и SUPER_ADMIN. Supervisor — `EmployeeWorkspace.isSupervisor` для конкретного Workspace | Supervisor одного Workspace не получает права руководителя в другом |
| Диалог | Сотрудник видит свободный или назначенный ему диалог в доступном Workspace; supervisor видит все диалоги своего Workspace; SUPER_ADMIN — своей Organization | Разрешение читать Workspace не разрешает все сообщения Workspace |
| Группы | Личные группы доступны только EMPLOYEE, включая supervisor; принадлежность проверяется по владельцу | SUPER_ADMIN не получает чужие группы и не приобретает личные группы через новый stream |
| Фильтры | PRIVATE — только владельцу, включая собственные фильтры SUPER_ADMIN; SYSTEM — читаются в Workspace, управляются supervisor/SUPER_ADMIN | PRIVATE и SYSTEM имеют разные аудитории |
| Клиенты | Базовые Client-данные доступны EMPLOYEE/SUPER_ADMIN своей Organization; ClientAccount и агрегаты ограничиваются доступными контекстами | Нельзя переносить ACL диалога на всю карточку Client или расширять доступ к сообщениям через Client |
| Прочтение | `Conversation.unreadCount` — сообщения диалога; unread-count API считает диалоги с unreadCount > 0 | Эти значения не равны unreadCount центра уведомлений; сохраняется семантика соответствующего REST endpoint |
| Обновление UI | Переписка опрашивается каждые 5 с, состояние открытого диалога — 10 с; Inbox слушает локальные `window` events; Telegram auth использует ограниченный polling 500 мс | Подключить общий механизм, сохранив generation guards, прокрутку, черновик и защиту скрытого экрана |
| Источники | Помимо контроллеров есть Kafka consumers, TDLib callbacks, восстановление Telegram при старте и scheduled Recent Chats Sync | Hooks только в REST-контроллерах дадут неполное покрытие |
| Готовность | Browser/native stream, центр уведомлений, outbox и push delivery пока отсутствуют | Подготовленная семантика не является подтверждением реализации |

### Источники аудита

Идентификаторы Bxx используются в каталоге для связи сценария с кодом. Наличие метода в таблице обозначает источник/потребителя изменения, а не уже существующую публикацию UI event.

| ID | Проверенный код |
|---|---|
| B01 | [CurrentUserService](server/src/main/java/kit/penny/clientbus/server/security/service/CurrentUserService.java), [UserRole](common/src/main/java/kit/penny/clientbus/common/enums/UserRole.java) |
| B02 | [ConversationService](server/src/main/java/kit/penny/clientbus/server/service/ConversationService.java), [ConversationController](server/src/main/java/kit/penny/clientbus/server/controller/ConversationController.java) |
| B03 | [MessageService](server/src/main/java/kit/penny/clientbus/server/service/MessageService.java), [MessageController](server/src/main/java/kit/penny/clientbus/server/controller/MessageController.java) |
| B04 | [MessageProcessingService](server/src/main/java/kit/penny/clientbus/server/service/MessageProcessingService.java), [OutboundMessageTransactionService](server/src/main/java/kit/penny/clientbus/server/service/OutboundMessageTransactionService.java) |
| B05 | [KafkaPlatformMessageConsumer](server/src/main/java/kit/penny/clientbus/server/kafka/consumer/KafkaPlatformMessageConsumer.java), [KafkaPlatformConversationConsumer](server/src/main/java/kit/penny/clientbus/server/kafka/consumer/KafkaPlatformConversationConsumer.java), [KafkaPlatformEventConsumer](server/src/main/java/kit/penny/clientbus/server/kafka/consumer/KafkaPlatformEventConsumer.java) |
| B06 | [TelegramInboundMessageListener](server/src/main/java/kit/penny/clientbus/server/connector/telegram/client/TelegramInboundMessageListener.java), [TelegramMessageSendListener](server/src/main/java/kit/penny/clientbus/server/connector/telegram/client/TelegramMessageSendListener.java), [TelegramMessageReadListener](server/src/main/java/kit/penny/clientbus/server/connector/telegram/client/TelegramMessageReadListener.java) |
| B07 | [KafkaOutboundMessageConsumer](server/src/main/java/kit/penny/clientbus/server/kafka/consumer/KafkaOutboundMessageConsumer.java), [KafkaErrorHandlerConfig](server/src/main/java/kit/penny/clientbus/server/kafka/config/KafkaErrorHandlerConfig.java) |
| B08 | [ConversationGroupService](server/src/main/java/kit/penny/clientbus/server/service/ConversationGroupService.java), [SavedConversationFilterService](server/src/main/java/kit/penny/clientbus/server/service/SavedConversationFilterService.java) |
| B09 | [ClientService](server/src/main/java/kit/penny/clientbus/server/service/ClientService.java), [ClientAccountService](server/src/main/java/kit/penny/clientbus/server/service/ClientAccountService.java), [ConversationRepository](server/src/main/java/kit/penny/clientbus/server/persistence/repository/ConversationRepository.java) |
| B10 | [ChannelService](server/src/main/java/kit/penny/clientbus/server/service/ChannelService.java), [ChannelAvailabilityService](server/src/main/java/kit/penny/clientbus/server/service/ChannelAvailabilityService.java) |
| B11 | [TelegramAuthorizationController](server/src/main/java/kit/penny/clientbus/server/connector/telegram/authorization/TelegramAuthorizationController.java), [TelegramAuthorizationService](server/src/main/java/kit/penny/clientbus/server/connector/telegram/authorization/TelegramAuthorizationService.java), [TelegramChannelAccountService](server/src/main/java/kit/penny/clientbus/server/connector/telegram/account/TelegramChannelAccountService.java) |
| B12 | [TelegramAuthorizationStateListener](server/src/main/java/kit/penny/clientbus/server/connector/telegram/client/TelegramAuthorizationStateListener.java), [TelegramClientStartupService](server/src/main/java/kit/penny/clientbus/server/connector/telegram/startup/TelegramClientStartupService.java), [TelegramClientManager](server/src/main/java/kit/penny/clientbus/server/connector/telegram/client/TelegramClientManager.java) |
| B13 | [EmployeeService](server/src/main/java/kit/penny/clientbus/server/service/EmployeeService.java), [EmployeeWorkspaceService](server/src/main/java/kit/penny/clientbus/server/service/EmployeeWorkspaceService.java) |
| B14 | [WorkspaceService](server/src/main/java/kit/penny/clientbus/server/service/WorkspaceService.java), [OrganizationService](server/src/main/java/kit/penny/clientbus/server/service/OrganizationService.java) |
| B15 | [KafkaSyncConversationHistoryConsumer](server/src/main/java/kit/penny/clientbus/server/kafka/consumer/KafkaSyncConversationHistoryConsumer.java), [RecentChatsSyncCoordinator](server/src/main/java/kit/penny/clientbus/server/service/RecentChatsSyncCoordinator.java), [RecentChatsSynchronizationService](server/src/main/java/kit/penny/clientbus/server/service/RecentChatsSynchronizationService.java), [RecentChatsSyncScheduler](server/src/main/java/kit/penny/clientbus/server/service/RecentChatsSyncScheduler.java) |
| B16 | [useConversationMessages](ui/src/components/conversations/useConversationMessages.ts), [ConversationView](ui/src/components/conversations/ConversationView.tsx), [useInboxConversations](ui/src/components/conversations/list/useInboxConversations.ts) |
| B17 | [AuthContext](ui/src/auth/AuthContext.tsx), [WorkspaceContext](ui/src/workspace/WorkspaceContext.tsx), [AppLayout](ui/src/components/layout/AppLayout.tsx), [Sidebar](ui/src/components/layout/Sidebar.tsx), [AuthController](server/src/main/java/kit/penny/clientbus/server/controller/AuthController.java), [JwtAuthenticationFilter](server/src/main/java/kit/penny/clientbus/server/security/jwt/JwtAuthenticationFilter.java) |
| B18 | [TelegramConnectionFlow](ui/src/components/channels/TelegramConnectionFlow.tsx), [ChannelsPage](ui/src/pages/ChannelsPage.tsx), [ClientsPage](ui/src/pages/ClientsPage.tsx), [ClientDetailsPage](ui/src/pages/ClientDetailsPage.tsx), [EmployeesPage](ui/src/pages/EmployeesPage.tsx), [WorkspaceDetailsPage](ui/src/pages/WorkspaceDetailsPage.tsx) |

## 2. Общие правила

1. **Сигнал обновления, персональная запись, попытка доставки и состояние операции различаются.** Получение SSE/push не изменяет бизнес-статус и не означает прочтения.
2. **Наблюдаемый результат — подтверждённое изменение.** Одинаковый UPDATE, повтор Kafka, запрос GET или команда, откатившаяся целиком, не создают новую бизнес-запись уведомления. Ошибка HTTP сама по себе не доказывает сохранённый FAILED. Внутренние auth-step конкретного коннектора не выдаются за сохранённый ChannelStatus и не образуют отдельный Notification-контракт; существующий интерактивный flow остаётся в реализации подключения канала.
3. **LIVE/HISTORY_IMPORT/SYNC — намерение обработки.** LIVE обозначает текущее бизнес-изменение, в том числе из HTTP; HISTORY_IMPORT — импорт сообщений истории; SYNC — snapshot/периодическое согласование. Отдельно фиксируются actor и способ запуска. Origin/actor назначаются доверенной BE-точкой входа, а не принимаются как непроверенное утверждение клиента. Давность сообщения не определяет origin. Reconnect/REST recovery — режим чтения клиента, не новое бизнес-возникновение.
4. **Идемпотентность сообщения и уведомления независима.** Сначала импортированное, затем полученное LIVE-сообщение может создать ровно одно разрешённое LIVE-уведомление. Повтор LIVE не создаёт второе. Исходящее сообщение/его echo не становится входящим уведомлением.
5. **Аудитория фиксируется в транзакции бизнес-возникновения.** Гонка нового сообщения с назначением должна иметь определённый результат согласно порядку изменения диалога, а не времени чтения outbox. Сохраняется notification occurrence с первоначальным решением о получателях, включая пустую аудиторию; одной уникальности recipient/key недостаточно. Повтор использует сохранённое решение. HISTORY_IMPORT/SYNC не фиксирует решение за ещё не обработанное LIVE-возникновение. Перед отправкой и REST-выдачей проверяются текущие права; утраченный доступ может отменить доставку старому адресату, но не переназначает запись автоматически новому. Occurrence и его аудитория переживают очистку центра в поддерживаемом окне повторов.
6. **Область данных важнее общей роли.** Получение события никогда не даёт дополнительных прав. Личные уведомления видны только получателю; SUPER_ADMIN не читает чужой центр. Одна глобальная ClientAccount identity не означает рассылку между Organization: затронутые области вычисляются по разрешённым связям использования. В первой версии разрешение перед выдачей чувствительного сигнала/DTO и перед push проверяется по БД; допустимы пакетные проверки. Задержанная Kafka-инвалидация кэша не является подтверждением текущего ACL. Уже отправленные данные нельзя отозвать; гонка после проверки имеет эту явную границу.
7. **Удаление требует сигнала очистки.** Бывшая область/аудитория сохраняется минимально; отсутствие target не блокирует очистку ранее открытого списка/карточки. Сигнал не содержит закрытый DTO, названия личных объектов или новых приватных участников.
8. **Три вида unread не смешиваются.** `Conversation.unreadCount`, агрегаты непрочитанных диалогов и число непрочитанных персональных уведомлений читаются из соответствующего REST. `notification.readAt` не посылает read receipt платформе. Обычное прочтение диалога автоматически не читает центр. UI отмечает конкретное уведомление после разрешённого открытия через него; для массового прочтения есть отдельная команда.
9. **Собственная команда обновляет все разрешённые сессии.** Локальный REST-ответ и push-echo не дублируют результат/баннер. Самоназначение не создаёт персональное «Вам назначен диалог»; асинхронная окончательная ошибка сохраняется и для инициатора, но уже показанная inline-ошибка не дублируется toast в том же контексте.
10. **Срок показа и срок хранения различаются.** После `alertExpiresAt` запись может оставаться в центре, но новый toast/push не создаётся. Retry не обновляет срок. Origin не выводится из времени, а допустимость слишком старого источника отдельно ограничивается поддержанным окном повторов. Численные TTL/retention/idempotency окна задаются в 11.2, без обещания бесконечной дедупликации.
11. **Отсутствие SSE не доказывает отсутствие пользователя; наличие SSE не доказывает внимание.** Backend не исключает долговечное уведомление только из-за открытого соединения. Клиент может подавить баннер для видимого активного target; подавление не подтверждает прочтение.
12. **Начальная загрузка и replay не создают новые оповещения.** После ready/reconnect открытые данные согласуются через REST. Исторические записи центра не переиздаются как `notifications.created`; административный replay подавляет новые пользовательские эффекты. При включении feature flag старые записи БД не превращаются автоматически в новые уведомления.

Изменение каталогов не перезаписывает пользовательский ввод. Обновлённый в другой сессии saved filter обновляется в каталоге, но текущие условия/черновик меняются только при повторном применении пользователем. После удаления выбранного saved filter связь с ним снимается, а допустимые текущие условия остаются ручной query. Удалённая личная группа убирается из выбора; UI переходит в безопасное базовое представление без её groupId. При потере прав очистка недоступного scope имеет приоритет перед сохранением его данных.

## 3. Аудитории и матрица ролей

Все аудитории требуют enabled-пользователя, существующего профиля сотрудника и нужной Organization. W/O обозначают конкретные Workspace/Organization. Роль supervisor проверяется отдельно для W. В уведомлении могут быть получатели вне выбранного в UI Workspace; переход должен сначала проверить цель и доступную область.

| Код | Аудитория |
|---|---|
| C | Пользователи с текущим REST-доступом к конкретному диалогу: сотрудник со своим/свободным диалогом в W, supervisor W, SUPER_ADMIN своей O |
| W | Участники W и SUPER_ADMIN O для безопасного обновления каталогов/списков; это не разрешение отправлять им все conversationId/сообщения |
| O | Пользователи O для общих разрешённых Client/Organization-данных; только поля/ресурсы, допустимые соответствующим REST |
| P | Конкретный получатель/владелец employeeId, все его разрешённые сессии; администратор не добавляется автоматически |
| M | Руководители W: supervisor W и SUPER_ADMIN O; используется fallback окончательной ошибки без доступного ответственного |
| A | Только SUPER_ADMIN своей O, с проверкой нужного канала/Workspace; аудитория действий над подключением |
| G | Владелец личной группы, только EMPLOYEE, включая supervisor, с доступом к W |
| F | Владелец PRIVATE-фильтра с доступом к W; для SUPER_ADMIN — только его собственный фильтр |
| D | Разрешённые читатели каталога сотрудников: SUPER_ADMIN O, supervisor соответствующего W; отдельный собственный профиль получает P |
| OLD | Минимальная аудитория очистки прежнего scope/доступа; не получает приватный payload и не обязана сохранять доступ к удалённому target |

| Ресурс/действие | EMPLOYEE без supervisor W | EMPLOYEE supervisor W | SUPER_ADMIN O |
|---|---|---|---|
| Читать диалог/сообщения W | Свободный или свой, при членстве W | Все в своём W | Все в O |
| Принимать подробные invalidation диалога | По C | По C | По C |
| Назначать другого/освобождать чужой диалог | Нет | Да в W | Да в O |
| Взять свободный/освободить свой | Да в W | Да в W | Self-assignment отсутствует |
| Retry исходящего | Только назначенный себе диалог, при доступности операции | Как сотрудник: назначенный себе диалог | По существующему административному ACL своей O |
| Личные группы | Только свои | Только свои | Недоступны |
| PRIVATE-фильтры | Только свои | Только свои | Только свои |
| SYSTEM-фильтры | Чтение W | Чтение/управление W | Чтение/управление O |
| Client, базовый профиль | Своя O | Своя O | Своя O |
| ClientAccount/агрегаты/разговоры клиента | По ACL конкретного REST и доступным W; сообщения дополнительно по C | Аналогично, с supervisor-правами в W | По O и ACL операции |
| Состояние канала | Чтение своего W | Чтение своего W | Чтение/управление O |
| Управление подключением канала, общая policy | Нет | Нет | Да в O по существующим правам канала; один набор проверок для всех реализаций, G01 не создаёт отдельный Telegram scope |
| Каталог сотрудников | Свой профиль; не весь каталог | Каталог своего W | Каталог O |
| Менять membership/supervisor/enabled сотрудника | Нет | Нет | Да в O |
| Изменять/удалять Workspace | Нет | Нет | Да в O |
| Центр уведомлений/read-all/установки | Только свои | Только свои | Только свои |
| History operation | Своя, при доступе к диалогу и внешней операции | Своя, с теми же проверками | Своя; чужие результаты автоматически не раскрываются |

Матрица описывает права, необходимые для routing, и не расширяет существующие команды. Если backend имеет более узкое правило для конкретного endpoint, gateway не расширяет его; обнаруженные обходы проверки не считаются разрешением.

## 4. Каталог источников и обновлений

`UI-only` означает invalidation/REST без записи в центре и без push. N1–N5 определены в разделе 5. Идентификаторы E01–E40 — идентификаторы сценариев, не окончательные названия wire events/DTO. REST-ресурсы указаны семантически; точные DTO и endpoints — 11.2.

### Диалоги и сообщения

| ID | Триггер / источник | Подтверждённое изменение, origin | Аудитория / что перечитать | Персональное поведение |
|---|---|---|---|---|
| E01 | TDLib inbound → Kafka message; B03–B06 | Новое входящее CLIENT-сообщение, LIVE; dedup по бизнес-сообщению | C: переписка, Inbox с текущими query/sort, unread-агрегаты; разрешённые Client last-contact aggregates | N2 только активному назначенному; для свободного диалога UI-only |
| E02 | REST send/forward/retry preparation; B03/B04 | Создание исходящего/QUEUED, LIVE | C: переписка/статусы, Inbox preview/order, разрешённые Client aggregates | UI-only; успешная локальная команда не превращается в персональное уведомление |
| E03 | Сообщение, отправленное с внешнего Telegram-клиента; B04–B06 | OUTBOUND echo, LIVE; сохранение/обогащение собственного сообщения | C: переписка/Inbox/агрегаты; merge по message identity | UI-only, никогда N2 |
| E04 | Platform event, send/read callbacks, bulk markReadUpTo; B03/B05/B06 | Эффективное SENT/DELIVERED/READ изменение, LIVE | C: затронутые сообщения, включая ранее загруженные старые страницы | UI-only; повтор статуса не создаёт запись |
| E05 | Processing/delivery failure, exhausted retry/DLQ; B03/B04/B06/B07 | Сохранённая окончательная ошибка конкретной попытки, LIVE | C: сообщение/статус; M при отсутствии доступного ответственного | N3; transient exception или ожидание reconnect без terminal failure не оповещает |
| E06 | REST retry и повторные Kafka delivery attempts; B03/B04/B07 | Новая пользовательская попытка меняет состояние; технический повтор той же попытки — no-op | C: актуальные processing/delivery status | UI-only на старте retry; новый окончательный failure может дать N3 для новой бизнес-попытки |
| E07 | Создание диалога HTTP/inbound/platform snapshot; B02/B04/B05 | Появление диалога; origin источника сохраняется | W: безопасный Inbox/list invalidation; подробности только C | Сам факт появления не создаёт персональную запись; N1/N2 применяются независимо по своим условиям |
| E08 | Назначение/переназначение; B02 | Только эффективный переход assignee A→B, LIVE | C/W: обновить выборки Мои/Свободные/группы/Client conversations; OLD: очистить утраченное; G прежних memberships: перечитать группы | N1 новому адресату, если это назначение другим actor; прежнему — только очистка |
| E09 | Освобождение или self-release; B02 | assignee→null, очистка memberships, LIVE | W: новые свободные; C и OLD: переписка/карточки; прежние G: memberships | UI-only, без рассылки всем «появился свободный» |
| E10 | markAsRead; B02/B03 | Только фактическое изменение unread диалога, LIVE; метод может вернуть no-op | C/разрешённые читатели соответствующего агрегата: диалог/Inbox/count | UI-only; не менять notification.readAt и не обещать read receipt до его выполнения платформой |

### Личные представления и клиенты

| ID | Триггер / источник | Изменение, origin | Аудитория / что перечитать | Персональное поведение |
|---|---|---|---|---|
| E11 | CRUD личной группы; B08 | Состав каталога групп владельца, LIVE | G: группы/sidebar; при удалении — сброс недействительного выбранного представления | UI-only |
| E12 | Add/remove group member; автоматическая очистка при assignment; B02/B08 | Membership, LIVE | G/OLD: membership, групповая выборка и badges; C для подробностей диалога | UI-only; не раскрывать чужие названия/ID групп |
| E13 | PRIVATE filter CRUD; B08 | Собственный фильтр, LIVE | F: сохранённые представления и активная query при необходимости | UI-only |
| E14 | SYSTEM filter CRUD; B08 | Общий фильтр W, LIVE | W: системные представления; редактировать могут только M | UI-only |
| E15 | Create/update/enable/avatar Client; B09 | Базовый профиль Client, LIVE | O: Client list/search/card; затронутые разрешённые Inbox details | UI-only; не отправлять всю переписку вслед за Client-профилем |
| E16 | Delete Client; B09 | Удаление; связанные аккаунты/ссылки перечитать по фактическим результатам | O/OLD: список/карточка Client; затронутые C: правый блок/признак связи | UI-only; target может уже не существовать |
| E17 | ClientAccount get-or-create/update/profile discovery; B04/B09 | Создание/изменение профиля identity, LIVE либо SYNC | Разрешённые W/O по использованию аккаунта: picker/accounts; C: avatar/name/details | UI-only; один global account не объединяет tenant-аудитории |
| E18 | Link/unlink/reassign account↔Client; B09 | Изменение связи, LIVE | Старые и новые разрешённые Client/account views; C: badges/детали; OLD: убрать исчезнувшие ссылки | UI-only; не обещать удаление самих диалогов |
| E19 | Delete ClientAccount; B09 | Удаление identity и фактически исчезнувших зависимостей, LIVE | Минимальные прежние области/OLD: account lists, связанные Client views и диалоги | UI-only; повторные GET определяют фактическую доступность, без закрытого tombstone DTO |

### Каналы, сотрудники и области доступа

| ID | Триггер / источник | Изменение, origin | Аудитория / что перечитать | Персональное поведение |
|---|---|---|---|---|
| E20 | Channel CRUD, account edit; B10 | Каталог/профиль канала, LIVE | W: channels/options; C: доступность внешних операций; OLD при удалении | UI-only |
| E21 | TDLib callback, lifecycle enable/disable/disconnect, startup restore; B11/B12 | Эффективный persisted ChannelConnectionStatus, LIVE; стартовое восстановление не превращает всё в новые инциденты | W: состояние канала, composer/retry/history availability | N4 только для нового неожидаемого/требующего вмешательства инцидента; штатная команда disable/disconnect без N4 |
| E22 | Внутренний Telegram WAIT_CODE/PASSWORD/EMAIL/READY/ERROR/CLOSED; B11/B12 | Технические стадии существующего подключения; coarse CONNECTING может не меняться | Существующий auth flow под общей policy канала; дополнительные операции/scopes в IChannelConnector или Notification не добавляются | Существующее интерактивное поведение; отдельного global auth-step event/owner route не требуется. В Notification попадает общее persisted состояние канала по E21; секреты не публикуются |
| E23 | Workspace create/update/avatar; B14 | Каталог/профиль W, LIVE | W/A и новая разрешённая область: workspace list/header/details/options | UI-only; создание не выдаёт membership всем сотрудникам |
| E24 | Delete Workspace; B14 | Потеря W и зависимых ресурсов, LIVE | OLD: снять текущий W, очистить запросы/кэш/центры и перечитать доступные W; A: список | UI-only; никакого требования читать уже удалённый W для очистки |
| E25 | Membership grant/revoke; B13 | Изменение доступа employee↔W, LIVE | P затронутого + D/A: me/workspaces/directory; OLD при revoke: закрыть scope, затем refetch | UI-only/control; не создавать персональную запись с недоступным target |
| E26 | Supervisor flag в membership; B13 | Изменение полномочий в W, LIVE | P + D/A: identity/workspaces/directory; перечитать зависящие от роли выборки; OLD при понижении | UI-only; флаг W не переносится на остальные W |
| E27 | Employee create/update/avatar; B13 | Профиль сотрудника, LIVE | P: me/header; D: разрешённые каталоги/assignment selectors; avatar — по собственному REST ACL | UI-only; обычным сотрудникам не передавать весь EmployeeDto |
| E28 | Employee enabled/delete; B13 | Блокировка/удаление, LIVE | P: минимальное прекращение сессии; A/D: каталог; W/C: последствия только если действительно изменились assignments/связи | UI-only/control; прекращение SSE и отозванные установки не требуют успешного me GET заблокированного |
| E29 | Username/email/password/reset; B13 | Изменение identity/security state, LIVE | P и разрешённые A: me/безопасное состояние; никаких паролей/хешей в event/центре/логах | UI-only; изменение credentials не объявляется глобальным отзывом JWT, пока такого механизма нет |
| E30 | Organization profile update/bootstrap; B14 | Общие разрешённые данные O; bootstrap — начальное состояние | O: me.organization/header/settings; чужую O не отправлять | UI-only; будущая multi-tenant маршрутизация не опирается на допущение «в БД одна O» |

### Операции, центр, устройства и восстановление

| ID | Триггер / источник | Изменение / origin | Аудитория / что перечитать | Персональное поведение |
|---|---|---|---|---|
| E31 | History page import, cached-end marker; B03/B15 | Сохранённые сообщения/historyStartReached, HISTORY_IMPORT | C: нужная страница истории/границы; запрос/результат операции — P инициатора | UI-only; нет N2/N5 за каждую страницу и нет toast об успешной подгрузке |
| E32 | Recent Chats Sync по scheduler/startup; B04/B05/B15 | Conversations/accounts/snapshot, SYNC | Разрешённые W/C: Inbox, профили и агрегаты; сохраняются текущие правила unread/lastMessage | UI-only; фоновые прогоны не создают персональные «синхронизация завершена» |
| E33 | History operation accept/run/success/fail/watchdog; B03/B15, целевая миграция 11.10 | Сохраняемый status/result; частично импортированные сообщения остаются кэшем | P инициатора с текущим C: operation/status/discovery; C: импортированные данные | Для обычной подгрузки inline status/error без N5; N5 только при отдельно введённой явной длительной команде |
| E34 | Первая вставка персональной записи; целевая 11.8 | Новая notification с устойчивым dedup key | P: notifications.created → разрешённый detail/summary; конкретный ресурс зависит от N-категории | Правила toast/push раздела 5; существующий read/expired target не оповещать повторно |
| E35 | Read/read-all, изменение ACL цели, retention центра; целевая 11.8 | Изменение доступного списка/readAt/summary | P: notifications.invalidated → list/summary; при read-all сохраняется граница первой команды | Нового created/toast/push нет; message read receipt не вызывается |
| E36 | Installation register/rotate/logout/account switch; целевая 11.8.4 | Endpoint/binding ownership/version/enabled | P конкретной установки: REST registration ack; worker использует актуальные версии | UI-only; старые задания не переносятся другому аккаунту; уведомления центра не копируются на новое устройство |
| E37 | Push worker claim/retry/provider ack/reject/expiry; целевая 11.8.4 | Только delivery state; expiresAt неизменен | Backend diagnostics и собственный lifecycle регистрации | Нет нового notification/readAt; provider acceptance не подтверждает доставку/прочтение |
| E38 | Login/logout/смена пользователя/JWT expiry; B17 | Жизненный цикл конкретного клиента/соединения | P этого клиента: открыть/закрыть stream, отменить старые запросы, очистить scope; logout native отвязывает свою установку | UI-only/control; не завершать все остальные устройства без отдельной команды |
| E39 | Stream ready/reconnect/focus/смена W/pipeline recovery; целевая 11.4–11.7, B16/B17 | Режим согласования, без нового бизнес-изменения | Активные разрешённые query + личный summary; dirty во время fetch сохраняется | UI-only; исторические уведомления не воспроизводятся как новые |
| E40 | Переполнение очереди, consumer unavailable, несовместимая схема | Управление деградацией, без изменения бизнес-данных | stream.resync-required/close, ограниченный fallback, диагностика | UI-only; не создавать уведомления «потерян каждый event» и бесконечные retry с невалидным JWT |

## 5. Персональные категории и правила показа

Следующие продуктовые решения приняты 21.10.2026 как базовая семантика 11.1. Основное управление push — разрешённая активная установка пользователя; гибкая preference engine остаётся за пределами этапа.

| Категория | Когда создаётся запись | Получатель | Когда не создаётся | Ключ бизнес-возникновения / актуальность |
|---|---|---|---|---|
| N1 ASSIGNMENT | Эффективное назначение другому сотруднику | Новый активный assigned employee, с текущими правами | Self-assignment, A→A, освобождение, технический повтор | Получатель + occurrence изменения assignment; один и тот же переход не зависит от UUID публикации; alert прекращается при утрате назначения/доступа либо expiry |
| N2 INCOMING_MESSAGE | Новое LIVE-входящее CLIENT-сообщение в назначенном диалоге | Активный assigned employee, включая supervisor, если назначен он | Свободный диалог; OUTBOUND echo; HISTORY_IMPORT/SYNC; повтор LIVE; отсутствие доступного assigned | Получатель + business message identity + N2; alert прекращается при утрате доступа, readAt или expiry |
| N3 OUTBOUND_FAILED | Зафиксированная окончательная processing/delivery ошибка попытки | Текущий доступный assigned; если его нет/он неактивен или лишён доступа — M. Один человек не дублируется по ролям | Transient retry, QUEUED/PENDING без terminal failure, отказ команды без сохранённой ошибки | Получатель + message identity + business send-attempt occurrence + N3; следующий ручной retry — отдельная попытка, технический retry — прежняя |
| N4 CHANNEL_ACTION_REQUIRED | Новый неожидаемый инцидент ERROR/DISCONNECTED/нужна повторная авторизация, действительно требующий вмешательства | A, поскольку управление подключением в целевой policy — административное | Штатный disable/disconnect; повтор того же callback; ожидаемый WAIT_CODE в инициированном flow; очередной startup snapshot того же инцидента | Получатель + channel + incident occurrence; восстановление/сознательное отключение закрывает актуальность alert, а новое реальное падение после восстановления — новый incident |
| N5 MANUAL_OPERATION_RESULT | Успех/окончательная ошибка отдельно запущенной пользователем длительной операции, которую можно покинуть | Инициатор, если текущий доступ сохранён | Каждая страница history, scheduled Recent Chats Sync, повтор terminal state, операция без явной команды пользователя | Получатель + operationId + terminal occurrence; категория зарезервирована: в проверенном UI обычная history pagination её не активирует |

**Свободные диалоги в базовой версии:** оперативно обновляются у W, подробности читаются по C; broadcast push/персональные записи всем сотрудникам или руководителям не создаются. Дополнительное оповещение о свободных — отдельное продуктовое решение.

**Web/native foreground:** новое разрешённое `notifications.created` после ready позволяет получить detail. Для SSE-баннера проверяются серверная граница текущей stream-сессии, unread, alertExpiresAt, актуальность категории и сохранённая на клиенте дедупликация по аккаунту/notificationId. Граница и ограниченное хранение dedup задаются контрактом 11.2; повтор старого created после нового ready сам по себе не даёт права показать баннер. На границе reconnect допустим пропуск баннера с сохранением записи в центре. Для активного видимого диалога N2 баннер подавляется; запись в центре сохраняется. Read/resync/пагинация списка не являются новым уведомлением. Наличие видимого диалога само по себе не посылает notification.read API.

**Native background:** backend планирует push для активных установок получателя при создании новой разрешённой N-записи. Реальная ОС может задержать/не показать push. В payload — общий текст, opaque notificationId и версия; ни сообщение, ни данные клиента, ни JWT. После нажатия — правильный аккаунт, REST и ACL, затем переход и явный read соответствующего уведомления. Безопасная ошибка открытия не отмечает чужую/недоступную запись прочитанной.

**Несколько устройств:** одна запись с одним readAt, несколько delivery по установкам. SSE и push дедуплицируются клиентом по notificationId. Уже принятый провайдером push может появиться после прочтения или logout; он остаётся общим, REST не выдаёт чужую цель. ReadAt не обещает немедленное снятие системного баннера на остальных устройствах.

**Истечение/неактуальность:** перед каждой отправкой проверяются права, bindingVersion/endpointVersion и актуальность категории. Уже прочитанный/разрешённый и затем утративший актуальность alert не отправляется заново. Автоматическая отмена ещё не отправленных delivery не удаляет допустимую запись центра. Повтор источника после очистки центра не создаёт её снова в поддерживаемом окне dedup.

## 6. Гарантии и восстановление

| Граница | Обязательная гарантия | Чего она не подтверждает |
|---|---|---|
| Domain transaction → notification/outbox/delivery intent | Атомарное сохранение; rollback не оставляет персональную запись или задание. Дедуп-конфликт обрабатывается штатно | Успех отправки в Kafka, ОС или браузер |
| Outbox → Kafka | Повторяемая публикация после commit, стабильный eventId, published после ack; восстановление после lease/crash | Exactly-once и бизнес-порядок между разными сущностями |
| Kafka → каждый gateway | Отдельная group на gateway; ready после готовности подписки; локальные очереди ограничены | Непрерывный журнал браузерного replay; автоматический failover внутренних ресурсов коннекторов |
| Gateway → client SSE | Оперативные минимальные сигналы и честная деградация pipeline | Что клиент получил/отрисовал каждое событие, прочитал target или остаётся внимательным |
| REST reconciliation | После ready/focus/reconnect/W change — актуальные доступные данные; single-flight + dirty не теряет требование повторного fetch | Мгновенное согласование при отсутствии сети; общий снимок всех независимых экранов |
| Provider → устройство | TTL/retry/binding/ACL соблюдаются на BE; accepted/rejected учитываются отдельно | Доставку, пробуждение при любом состоянии ОС, показ/прочтение и отсутствие повторов |
| Центр/read-all | Свои доступные записи, единая ACL списка/count/read; повтор request key не читает новые arrivals | Что прочитано сообщение платформы или снят баннер ОС |
| History operation | 202 после commit; состояние и результат доступны независимо от HTTP/future; terminal result защищён claimVersion | Атомарный rollback всех уже импортированных страниц или отмену внешнего вызова |

Алгоритм клиента: подписаться → получить ready → перечитать активные ресурсы и summary → объединять invalidation. На resource/query — один запрос; событие во время него устанавливает dirty, разрешённый ответ показывается, затем выполняется следующий ограниченный refetch. Смена пользователя/W/query и отзыв доступа отменяют старый scope; обычная invalidation не должна бесконечно отменять чтения. Ошибка сохраняет dirty. Скрытый экран не опрашивается; при возврате новые сообщения дочитываются до прежней границы без пропуска страниц и перезаписи более новых локальных ответов.

Редкая контрольная сверка и fallback ограничивают ущерб потерянного сигнала; интервалы и нагрузочные пределы выбираются до приёмки производительности. Живой heartbeat не маскирует отказ consumer/outbox. Постоянный поток событий не означает запрос на каждый event и не должен лишать UI возможности показать результат.

### 6.1. Зафиксированные решения последнего архитектурного ревью

Решения R01–R07 приняты 21.10.2026. Здесь зафиксированы обязательные свойства; точные DTO, SQL и параметры разрабатываются в 11.2 после согласования его плана.

| ID | Решение | Реализация и проверка |
|---|---|---|
| R01 | Устойчивый notification occurrence хранит первоначальную аудиторию, включая пустую. Повтор LIVE после A→B или null→B не создаёт новую N2 для B. Импорт не закрывает право первого LIVE принять решение | 11.2: идентичность, модель и retention; 11.8: атомарное решение/записи; T25 |
| R02 | sendAttemptId проходит команду, connector и результат отправки. Ручной retry создаёт новую попытку, технический retry сохраняет прежнюю. Поздний статус не меняет новую попытку и не создаёт для неё N3; для callback без attemptId определяется надёжная привязка к внешней отправке и безопасное поведение при неоднозначности | 11.2: контракт и совместимость; 11.8: до включения N3 — условные переходы и интеграция; T26. Это отдельная идентичность от claimVersion push worker |
| R03 | Авторитетный текущий ACL проверяется по БД перед чувствительной выдачей и push, вне Kafka consumer socket loop; проверка может быть пакетной. Недоступность policy/БД не разрешает отправку по старому кэшу. Минимальный сигнал очистки бывшей области остаётся допустимым | 11.3: policy с явным subject; 11.4/11.8: применение; T27. Кэш требует отдельного протокола подтверждения актуальности; уже отправленные данные отозвать нельзя |
| R04 | stream.ready задаёт серверную границу допустимости новых SSE-alert, клиент сохраняет ограниченный dedup по аккаунту/notificationId. Delayed/replayed created, readAt и expiry проверяются до баннера; SSE и foreground push используют общий dedup. На границе reconnect допустима потеря баннера с сохранением центра | 11.2: точный контракт границы без browser replay/cursor; 11.4/11.8: реализация; T28. Гарантии exactly-once показа и межвкладочного единственного баннера не вводятся |
| R05 | Клиент сохраняет Idempotency-Key до POST History Sync. Повтор с тем же ключом/параметрами в объявленном окне возвращает ту же операцию, включая SUCCEEDED/FAILED; права проверяются заново. activeOnly discovery служит дополнением и не заменяет восстановление уже завершённой операции | 11.2: ответ, срок ключа/результата, безопасная ошибка после очистки; 11.10: клиент/API; T29. До истечения окна нельзя удалить единственную связь ключа с результатом |
| R06 | Сквозной origin покрывает LIVE, History Import и Recent Chats до включения N2. Массовый импорт не должен ждать миграции history operations, чтобы перестать создавать персональные эффекты | 11.2: контракт; 11.5–11.8: propagation и проверка до включения центра; 11.10: повторная регрессия; T14 |
| R07 | Постоянная ошибка outbox переводит запись в наблюдаемый карантин без блокировки остальных записей и без ложного published. Временный/неоднозначный сетевой отказ использует backoff и стабильный eventId. Ручной повтор сохраняет идентичность события и проходит проверку terminal state связанной операции | 11.2: состояния/ошибки/повтор; 11.5: relay/метрики; 11.10: согласованный terminal outcome и deadline history operation; T30. UI восстанавливается REST; карантин команды не означает успешное выполнение |

## 7. Подтверждённые разрывы и зависимости реализации

| ID | Наблюдение аудита | Обязательное действие / этап |
|---|---|---|
| G01 | В существующем HTTP flow подключения Telegram start/status/code/password/email/enable/disable/disconnect не повторяют все общие проверки канала; `find` имеет отдельную workspace-проверку. Это наблюдение реализации, а не дополнительная capability | 11.3: применить общую policy существующих команд канала на HTTP-границе, с теми же Organization/Workspace/ролью для всех реализаций. Не вводить Telegram scopes/права и HTTP SecurityContext-зависимость в startup/callback |
| G02 | OrganizationService.getOrganization(id) проверяет принадлежность текущего employee своей Organization, затем читает переданный id без сравнения id с текущей O | 11.3: проверить именно target Organization; для новых событий всегда вычислять O на BE. Изолированный негативный тест чужого id |
| G03 | CurrentUserService основан на SecurityContextHolder; Kafka/TDLib/startup потоки не являются HTTP-запросами | 11.3: явный subject и общая policy; actor отсутствует у системного источника, но получатели вычисляются сервером |
| G04 | Общее ChannelConnectionStatus изменяется также из внутренних listener/startup конкретного коннектора; auth-step может измениться внутри того же coarse CONNECTING | 11.9: охватить все persisted writer paths общим транзакционным outbox для состояния канала. Внутренние auth-step не расширяют Notification scope и остаются в существующем flow подключения |
| G05 | В имеющейся обработке нет сквозного origin и устойчивого notification occurrence для send attempt/assignment/channel incident | 11.2: определить идентичности, первоначальную аудиторию и propagation origin; не использовать eventId новой Kafka-попытки либо `result.existed()` как универсальную дедупликацию уведомления. По R02/R06 реализовать origin до включения N2 и защиту sendAttemptId до N3 |
| G06 | History Sync ожидает process-local future; текущий TelegramClientManager хранит context локально как деталь реализации IChannelConnector | 11.10: сохраняемая операция вызывает общий IChannelConnector через registry. 11.7/11.9: проверить общую недоступность/ошибку коннектора; SSE fanout не объявляется HA коннекторов. Отдельный resolver/маршрут владельца Telegram в Notification не вводится |
| G07 | AuthController.logout возвращает 204 без серверного отзыва JWT. Новый refresh/revocation механизм отсутствует | 11.6/11.8: честный local logout и отвязка своей установки; enabled/expiry проверяются. Глобальный logout/password-based JWT revocation не обещать без отдельной реализации |
| G08 | Центр, delivery, registration и native client ещё не реализованы | 11.8: модель/API/worker с test provider; production SDK/credentials/устройства — отдельный native checklist. Проверка mock не закрывает mobile release |

Эти наблюдения получены чтением кода, без вызовов живых приватных API или воспроизведения атак. G01/G02 — требования к закрытию разрывов безопасности, а не основания считать существующее слабое место разрешённым поведением. В 11.1 код не исправлялся.

## 8. Проверяемые сценарии приёмки

Это план последующих проверок, не результаты выполненных runtime-тестов. T01–T30 связываются с каталогом и архитектурными рисками; конкретные тестовые классы/fixtures выбираются при реализации соответствующего этапа.

| ID | Проверка | Ожидаемый результат |
|---|---|---|
| T01 | E01, назначенный/свободный/чужой диалог; EMPLOYEE, supervisor W1 и обычный в W2, SUPER_ADMIN другой O | Подробности только C; свободный обновляет Inbox без broadcast push; для назначенного одна N2 получателю |
| T02 | E03, внешнее исходящее/echo собственного сообщения | Merge сообщения без дубля и без N2 |
| T03 | E01/E31, LIVE→LIVE, IMPORT→LIVE, LIVE→IMPORT, повтор после очистки центра | Максимум одна N2 для допустимого LIVE; импорт сам не создаёт уведомление; dedup переживает retention центра в своём окне |
| T04 | E08/E09, A→B, A→A, null→self, A→null; конкурентный LIVE | Нужные списки/группы обновляются; одна N1 только при назначении другим; прежний scope очищается; аудитория LIVE определяется транзакцией |
| T05 | E04/E05/E06, queued/transient failure/final failure/повтор и новый manual retry | N3 только на сохранённый terminal failure бизнес-попытки; provider callbacks и DLQ не создают дубль |
| T06 | E10/E35, read диалога против notification read/read-all | Раздельные unread и read receipts; backend no-op не генерирует ложное изменение |
| T07 | E11–E14, два владельца, supervisor и SUPER_ADMIN | Чужие личные группы/PRIVATE-фильтры не раскрываются; SYSTEM виден W и управляется M |
| T08 | E15–E19, доступный Client с ограниченными account/conversation contexts | Общая Client metadata не раскрывает чужие сообщения; старые/новые link views и badges согласуются |
| T09 | E20–E22, сохранённое падение, повтор callback, ожидаемое подключение, explicit disable, восстановление и новый incident | Только новое общее persisted состояние, требующее действия, даёт N4 для A; внутренние auth-step не создают отдельные Notification events/scopes и не меняют существующий flow |
| T10 | G01, существующие команды управления каналом через разные реализации, не-администратор и чужой channelAccountId | Единая серверная policy до выполнения команды/выдачи секретов; Telegram не получает отдельных прав, обычный доступ к ChannelsPage не даёт право управления |
| T11 | E24–E28, удаление target/W, revoke membership, supervisor downgrade, enabled=false | Минимальная очистка доставляется без чтения удалённого объекта; старые запросы не возвращают закрытые данные; другие W не затрагиваются без причины |
| T12 | G02/E30, target Organization не совпадает с текущей | REST и routing отказывают; ID не превращает пользовательский event в межорганизационный |
| T13 | E29/E38, login/logout/account switch/expiry, параллельные устройства | Scope/credentials/запросы прежнего клиента очищены; logout одного клиента не выдаётся за logout-all; секреты не попадают в события |
| T14 | E31–E33, крупный импорт и scheduled sync одновременно с LIVE | Нет notification storm и N5 за страницу; сохранены cache/history/существующие unread правила; LIVE не теряется в dedup импорта |
| T15 | E33, потерян ответ 202, reload, timeout одновременно с результатом, старый claim | Своя операция находится через discovery; terminal state не воскрешается; чужой initiator/утративший C не читает результат |
| T16 | E34/E35, две сессии, read-all, новое arrival между первым запросом и повтором того же key | Новое остаётся непрочитанным; повтор не выполняет новый UPDATE; другой requestHash по тому же key отклоняется |
| T17 | E34/E39, начальная загрузка/reconnect/старый created, read до показа, expiry | Нет повторных/просроченных toast; REST count/list актуальны; активный видимый target подавляет N2 banner без автоматического message read |
| T18 | E36, две установки/два аккаунта/ротация, поздняя ошибка прежнего endpoint | Общее readAt, отдельные delivery; binding не переходит чужому аккаунту; ошибка старой endpointVersion не отключает новую |
| T19 | E37, worker crash до/после provider ack, lease takeover, retry/TTL | Повтор возможен, но один логический delivery; старый claim не перезаписывает новый результат; TTL не продлевается и readAt не меняется |
| T20 | Transaction rollback и штатный dedup conflict | Rollback не оставляет notification/outbox/delivery; нормальный конфликт дедупликации не откатывает независимое успешное бизнес-изменение |
| T21 | E39, событие во время fetch/ошибки; постоянная серия событий; A→B→A и собственный send response | Dirty сохраняется, разрешённые ответы показываются, refetch ограничен, старый scope не восстанавливается, draft/scroll не теряются |
| T22 | E40, отказ Kafka при живом SSE, два gateway, restart/rebalance, slow client/overflow | Честный resync/fallback; group на gateway; очереди ограничены; consumer не ждёт медленный socket; собственный push worker не размножается по gateway |
| T23 | Native checklist: foreground/background/force-stop, permission отказ, offline, tap/cold start, reinstall/logout | Проверить на физических Android/iOS при native-интеграции; потеря push не ломает REST recovery, payload безопасен; mock Stage 11 не считается прохождением |
| T24 | G06, разрешённая history/send команда, разные реализации IChannelConnector и недоступный коннектор | Выполнение через общий registry/IChannelConnector; Notification получает общие результаты/ошибки и не обращается к TelegramClientManager. Два gateway не требуют отдельного Telegram scope/owner route |
| T25 | R01, LIVE для A или свободного диалога → переназначение B → повтор LIVE; IMPORT→LIVE | Уведомление не переадресуется B, в том числе при первоначально пустой аудитории; импорт не подавляет первое LIVE-решение; occurrence переживает очистку центра в своём окне |
| T26 | R02, ручной retry → поздний FAILED/SENT прежней попытки, повтор результата и DLQ | Новая попытка не меняется старым результатом; N3 относится к своей попытке; неоднозначный callback не приписывается текущей попытке по одному messageId |
| T27 | R03, revoke/disable при задержке Kafka и устаревшем кэше, затем выдача DTO/SSE/push; отказ БД policy | Авторитетная проверка запрещает чувствительную выдачу после отзыва; отказ policy не разрешает отправку из кэша; допустим минимальный сигнал очистки; учтена граница уже отправленных данных |
| T28 | R04, новый ready/reload → старый created до TTL, повтор в текущей сессии, SSE+foreground push | Граница stream и сохранённый dedup подавляют старый/повторный баннер; центр остаётся доступен; сбой/очистка клиентского хранилища не считается exactly-once гарантией |
| T29 | R05, потерян 202 → операция завершилась → reload/повтор POST с тем же ключом | Возвращается исходная terminal operation без нового запуска; activeOnly может быть пустым; другой payload по ключу отклоняется, чужой/недоступный результат не раскрывается |
| T30 | R07, невалидный payload outbox, постоянная ошибка публикации, timeout ack, ручной повтор после deadline history operation | Карантин наблюдаем и не блокирует другие записи; published не подделывается; retry сохраняет eventId; terminal operation не воскресает, UI имеет REST recovery |

## 9. Граница завершения 11.1 и следующего этапа

Подготовлены и приняты 21.10.2026: карта B01–B18, аудитории и матрица ролей, каталог E01–E40, первоначальные категории N1–N5, правила origin/read/UX/mobile, гарантии, G01–G08, решения R01–R07 и критерии T01–T30.

Зафиксированные продуктовые решения: N1 без self-assignment; N2 только назначенному и без broadcast для свободных; N3 fallback руководителям W; N4 только администраторам с правом действия; N5 не активируется обычной пагинацией истории; read центра отделён от read диалога. Эти правила и R01–R07 являются входом 11.2.

План 11.2 согласован: wire types/DTO/OpenAPI, модели/уникальности/SQL-переходы, actor/origin/occurrence contracts, параметры TTL/retention/idempotency, registration ownership, общая граница IChannelConnector и совместимость. По последующим указаниям пользователя схема развивается через JPA/Hibernate ddl-auto=update, без новых миграций; Telegram не имеет отдельных возможностей/scopes в Notification. Артефакты — [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md); native UI и production push в этот этап не входят.

Статус: 11.1 завершён и принят пользователем 21.10.2026 как этап спецификации; runtime-доставка не реализована этим результатом. Этап 11.2 выполнен: контракты и Hibernate-модель с проверками зафиксированы в NOTIFICATION_CONTRACTS.md. План 11.3 согласован; общая policy и маршрутизация реализованы, протокол в NOTIFICATION_ACCESS.md. 11.4 согласован и выполнен; результат — NOTIFICATION_TRANSPORT.md.


Уточнения ревью 11.2 согласованы: общий registrationVersion и обязательный CAS для изменений installation; монотонный alertInvalidatedAt; completedAt/retainUntil у terminal delivery; отдельные creation/SSE/push/history-submission flags при сохранении read/status API. Конкретные правила закреплены в NOTIFICATION_CONTRACTS.md. Этап 11.3 согласован и выполнен; см. NOTIFICATION_ACCESS.md.

Статус 22.10.2026: этап 11.4 выполнен; транспорт и результаты проверок — [NOTIFICATION_TRANSPORT.md](NOTIFICATION_TRANSPORT.md). Этап 11.5 согласован и выполнен 22.10.2026: NOTIFICATION_OUTBOX.md. Этап 11.6 согласован и реализован: NOTIFICATION_UI.md.

Результат 11.5 (22.10.2026): транзакционный UI outbox/relay с claim fencing, retry/quarantine и метриками, propagation origin, глобальный provider и REST-обновление Inbox/переписки. Настоящий desktop/mobile browser E2E пройден; подробности и границы следующего этапа — [NOTIFICATION_OUTBOX.md](NOTIFICATION_OUTBOX.md). Миграций и коммитов нет. 11.6 согласован и реализован; протокол — NOTIFICATION_UI.md.

Результат 11.6 (22.10.2026): план с учётом ревью согласован и реализован; lifecycle/scope isolation, немедленная очистка закрытых ресурсов, общий dirty для SSE/локальных команд, подписки ресурсов Inbox и сохранение локального состояния описаны в [NOTIFICATION_UI.md](NOTIFICATION_UI.md). После повторного ревью прошли 232 UI-теста, TypeScript/Vite и 4 реальных desktop/mobile browser E2E. Уточнённый план 11.7 принят пользователем; реализация и приёмка — [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md). 11.8 требует отдельного согласования.

Результат 11.7 (22.10.2026): реализованы recovery/fallback/редкая сверка, bounded HTTP-бюджет и retry, hidden/offline pause, проверены несколько gateway и Kafka restart/rebalance/cleanup. Протокол — [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md). 255 UI-тестов, 30 BE-тестов и 8 реальных desktop/mobile E2E прошли; 11.8 не начат.
