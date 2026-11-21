# Stage 13.1 — Инвентаризация Telegram-базлайна (модуль `server`)

Артефакт подэтапа 13.1 от 13.11.2026. Контракт integration/dikidi, capability-матрица и доказательства — [CRM_CONNECTORS.md](CRM_CONNECTORS.md).

Read-only инвентаризация перед выносом коннекторов каналов в SPI-модули.
Все пути относительные от корня репозитория `D:\Projects\Java\ClientBus`.
Ничего в исходниках не изменено; файл лежит в `server/target/` (вне git).

Методика: полный grep `Telegram|TELEGRAM|Tdlib|tdlib` по `server/src`, `common/src`, `ui/`,
чтение всех классов пакета `connector/telegram`, SPI-пакета `connector/`, Kafka-обвязки,
конфигурации, тестов и UI.

---

## 0. Ключевые цифры

| Метрика | Значение |
|---|---|
| Классов в `server/.../connector/telegram/**` | **22** top-level класса (включая вложенные `TelegramMessageSendListener.Succeeded/Failed` ≈ 24 типа) |
| Внешних callers `IChannelConnectorRegistry` (вне telegram-пакета) | **6** (4 Kafka-консьюмера, HistoryOperationWorker, OutboundConversationService) + 1 внутренний (`TelegramAuthorizationService`) |
| Callers `ChannelAccountLifecycleRegistry` | **1 класс / 2 места** (`ChannelService.deleteChannel`, `ChannelService.disconnectChannelAccount`) |
| Тестовых классов, прямо посвящённых Telegram | **17** |
| Тестовых файлов, ссылающихся на `Telegram|TELEGRAM` (включая фикстуры) | **68** |
| REST-эндпоинтов «совместимых /telegram/*» | **9** (базовый путь `/api/channels/{channelAccountId}/telegram/*`) |
| Точек UI, предполагающих Telegram | **~12 файлов** |

---

## 1. SPI и реестры

### 1.1 `IChannelConnector` — контракт коннектора

`server/src/main/java/kit/penny/clientbus/server/connector/IChannelConnector.java`

| # | Метод | Строка | Примечание |
|---|---|---|---|
| 1 | `default DiscoveredClientAccount handle(DiscoverClientAccountCommand)` | :13 | default → `UnsupportedOperationException` |
| 2 | `default Optional<ClientAccountAvatar> handle(ReadClientAccountAvatarCommand)` | :17 | default → `Optional.empty()` |
| 3 | `boolean supports(ChannelType)` | :21 | выбор коннектора реестром |
| 4 | `ConnectorSendResult handle(SendMessageCommand)` | :25 | исходящая отправка |
| 5 | `void handle(MarkMessagesReadCommand)` | :29 | read-команда наружу |
| 6 | `List<PlatformConversationRequest> handle(SyncRecentChatsCommand)` | :33 | список недавних чатов |
| 7 | `SyncConversationHistoryResult handle(SyncConversationHistoryCommand)` | :37 | страница истории |
| 8 | `ClientAccountProfile handle(SyncClientAccountCommand)` | :42 | **не имеет ни одного вызова в main-коде** (мёртвый метод SPI, реализован только в Telegram) |

Зависимости контракта: `common.dto.conversation.PlatformConversationRequest`,
`common.dto.message.PlatformMessageRequest`, `common.enums.ChannelType`, `connector.command.*` —
и **одна утечка**: `SendMessageCommand` импортирует `server.service.ChannelAttachment`
(`connector/command/SendMessageCommand.java:4`).

### 1.2 Команды (`connector/command/`)

Все команды — records, `sealed interface ChannelConnectorCommand`
(`connector/command/ChannelConnectorCommand.java:3-10`, permits ровно 7):

| Класс | Поля | Файл:строки |
|---|---|---|
| `SendMessageCommand` | messageId, channelAccountId, recipientExternalId, type, content, `List<ChannelAttachment> attachments`, sendAttemptId | command/SendMessageCommand.java:9-17 (attach: `server.service.ChannelAttachment`, :4-5) |
| `MarkMessagesReadCommand` | channelAccountId, recipientExternalId, lastReadExternalId | command/MarkMessagesReadCommand.java:5-9 |
| `SyncRecentChatsCommand` | channelAccountId | command/SyncRecentChatsCommand.java:5-7 |
| `SyncConversationHistoryCommand` | channelAccountId, conversationExternalId, beforeExternalId, limit | command/SyncConversationHistoryCommand.java:5-10 |
| `SyncClientAccountCommand` | channelAccountId, clientAccountId, externalId | command/SyncClientAccountCommand.java:6-10 |
| `DiscoverClientAccountCommand` | channelAccountId, phone, username | command/DiscoverClientAccountCommand.java:5-9 |
| `ReadClientAccountAvatarCommand` | channelAccountId, externalId | command/ReadClientAccountAvatarCommand.java:5-8 |

### 1.3 Результаты / DTO SPI (`connector/`)

| Класс | Содержание | Файл |
|---|---|---|
| `ConnectorSendResult` | externalId (String), sendAttemptId | ConnectorSendResult.java:3-13 |
| `SyncConversationHistoryResult` | `List<PlatformMessageRequest>`, historyStartReached | SyncConversationHistoryResult.java:7-10 |
| `ClientAccountProfile` | username, phone, displayName, avatarUrl | ClientAccountProfile.java:3-8 |
| `ClientAccountAvatar` | byte[] content, contentType, externalPhotoId | ClientAccountAvatar.java:4-17 |
| `ClientAccountAvatarPreview` | contentType, base64 | ClientAccountAvatarPreview.java:4-8 |
| `DiscoveredClientAccount` | externalId, username, phone, displayName, preview | DiscoveredClientAccount.java:4-10 |
| `ChannelEvent` | channelAccountId, status | ChannelEvent.java:7-10 |
| `OutboundSendCompleted` | channelType, channelAccountId, messageId, sendAttemptId — «локальный» сигнал завершения, публикуется сервером, слушается Telegram-коннектором | OutboundSendCompleted.java:7-8 |

### 1.4 Событийная инфраструктура SPI (`connector/`)

| Класс | Роль | Обратная зависимость (нарушение будущего SPI) |
|---|---|---|
| `IChannelListener` (`IChannelListener.java:5`) | слушатель `ChannelEvent` | — |
| `AbstractEventDispatcher` (`AbstractEventDispatcher.java:5-22`) | диспетчер listeners | — |
| `ChannelSynchronizationListener` (`ChannelSynchronizationListener.java:10-44`) | на CONNECTED → `RecentChatsSyncCoordinator.request(...)` | **импортирует `server.service.RecentChatsSyncCoordinator` (:4)** |
| `IChannelEventHandler` (`IChannelEventHandler.java:5-9`) | `handle(PlatformMessageEvent)` | — |
| `ChannelEventHandler` (`ChannelEventHandler.java:8-27`) | делег в `IMessageProcessingService` | **импортирует `server.service.IMessageProcessingService` (:4)**; **сам bean никем не вызывается** (звонки `IChannelEventHandler` в main не найдены) |

### 1.5 Реестры

- `IChannelConnectorRegistry.getConnector(ChannelType)` — `IChannelConnectorRegistry.java:5-9`.
- `ChannelConnectorRegistry` (@Component, first-match по `supports`) — `ChannelConnectorRegistry.java:8-38`;
  при отсутствии коннектора — `IllegalStateException("No connector registered for channel type: ...")` (:32-37).
- `IChannelAccountLifecycle` — `supports(ChannelType)` + `disconnect(UUID)` — `IChannelAccountLifecycle.java:7-11`.
- `ChannelAccountLifecycleRegistry.getLifecycle(ChannelType)` (@Component) — `ChannelAccountLifecycleRegistry.java:9-33`.

### 1.6 Все callers `ChannelConnectorRegistry` / `IChannelConnectorRegistry` в server

| # | Caller | Метод | Что вызывает | file:line |
|---|---|---|---|---|
| 1 | `kafka/consumer/KafkaOutboundMessageConsumer` | `consume` | `getConnector(type)`; `handle(SendMessageCommand)` | kafka/consumer/KafkaOutboundMessageConsumer.java:136-139, 221-222 (тип из имени топика :63-64; после успеха `messageService.registerExternalAttempt` :272) |
| 2 | `kafka/consumer/KafkaChannelReadConsumer` | `consume` | `getConnector(type)`; `handle(MarkMessagesReadCommand)` | kafka/consumer/KafkaChannelReadConsumer.java:78-89 |
| 3 | `kafka/consumer/KafkaSyncRecentChatsConsumer` | `consume` | `getConnector(type)`; `handle(SyncRecentChatsCommand)` → `RecentChatsSynchronizationService.process` | kafka/consumer/KafkaSyncRecentChatsConsumer.java:126-137 |
| 4 | `kafka/consumer/KafkaSyncConversationHistoryConsumer` | `consume` | `getConnector(type)`; `handle(SyncConversationHistoryCommand)`; sync/async ответ | kafka/consumer/KafkaSyncConversationHistoryConsumer.java:131-137, 176-233 |
| 5 | `notification/history/HistoryOperationWorker` | `runOperation` | `registry.getConnector(execution.type())`; `handle(SyncConversationHistoryCommand)` | notification/history/HistoryOperationWorker.java:92-97 |
| 6 | `service/OutboundConversationService` | `create` | `connectors.getConnector(request.channelType()).handle(ReadClientAccountAvatarCommand)` | service/OutboundConversationService.java:60-61 |
| 7 (telegram) | `connector/telegram/authorization/TelegramAuthorizationService` | `find` | `connectors.getConnector(ChannelType.TELEGRAM).handle(DiscoverClientAccountCommand)` | connector/telegram/authorization/TelegramAuthorizationService.java:170-173 |

Callers `ChannelAccountLifecycleRegistry`:

| Caller | Метод | file:line |
|---|---|---|
| `service/ChannelService` | `deleteChannel` → `getLifecycle(type).disconnect(accountId)` | service/ChannelService.java:202-204 |
| `service/ChannelService` | `disconnectChannelAccount` → `getLifecycle(type).disconnect(accountId)` | service/ChannelService.java:271-273 |

Оба предварительно зовут `channelStatus.expectDisconnect(...)` (:201, :270).

---

## 2. Реализация Telegram (`connector/telegram/**`)

Корень: `server/src/main/java/kit/penny/clientbus/server/connector/telegram/`.
«Обратные зависимости» = импорты server-классов вне `connector/*` (репозитории JPA,
сервисы, `CurrentUserService`, `IAttachmentStorage`, security), которые нельзя
унести в SPI как есть.

### 2.1 Корневой пакет

| Класс | Назначение | Обратные зависимости (file:line) |
|---|---|---|
| `TelegramChannelConnector` (1290 стр.) | `IChannelConnector` для TELEGRAM: send text/image/audio (temp-файлы), read, recent chats, history, discover, avatar, profile | `server.service.ChannelAttachment` (:19), `server.storage.IAttachmentStorage` (:20), `StoredAttachmentMetadata` (:21); listener `OutboundSendCompleted` + прямой вызов `TelegramMessageSendListener.deleteTemporaryFiles` (:998-1005); numeric ID: `parseChatId` (:1088-1102), `parseMessageId` (:1104-1118), `Long.parseLong(externalId)` (:580-587); temp-файлы `clientbus-telegram-*` (:1007-1026); аватар в `attachmentStorage` (:679-732, имя `telegram-avatar-<id>.jpg` :719) |
| `TelegramConversationMapper` | `TdApi.Chat/User` → `PlatformConversationRequest` | нет серверных (чистый) |
| `TelegramClientAccountDiscovery` (package-private) | поиск пользователя по phone/username, preview/avatar с лимитами 256KiB/5MiB и дедлайнами | нет серверных; использует только TDLib; константы `PREVIEW_MAX_BYTES/AVATAR_MAX_BYTES` (:27-28) |

### 2.2 `authorization/`

| Класс | Назначение | Обратные зависимости |
|---|---|---|
| `TelegramAuthorizationController` | REST `/api/channels/{channelAccountId}/telegram/*` (:13): POST `/start` (:28), GET `/status` (:36), POST `/code` (:44), `/password` (:56), `/email` (:68), `/disable` (:80), `/enable` (:88), `/disconnect` (:96), GET `/find` (:104, no-store) | `server.security.policy.HttpAccessPolicy` + `AccessPolicy.Resource` (:6-7, ACL в самом коннектор-пакете!) |
| `TelegramAuthorizationService` (@Service) | start/status/code/password/email/disable/enable/disconnect/find; маппинг статусов TDLib (:198-217) | `persistence.entity.ChannelAccountEntity` (:13), `ChannelAccountRepository` (:14), `ClientAccountRepository` (:15, `findByChannelTypeAndExternalId(ChannelType.TELEGRAM, ...)` :178-185), `security.service.CurrentUserService` (:16, `requireWorkspaceAccess` :155-160, :175), проверка `ChannelType.TELEGRAM` (:147-148), registry (:170) |
| `TelegramAuthorizationStatus` | enum WAIT_PHONE_NUMBER/WAIT_CODE/WAIT_PASSWORD/WAIT_EMAIL/READY/ERROR/CLOSED | нет (:3-11) |

### 2.3 `account/`

| Класс | Назначение | Обратные зависимости |
|---|---|---|
| `TelegramChannelAccountService` (@Service, реализует `IChannelAccountLifecycle`) | `supports(TELEGRAM)` (:37-40); `disconnect` = expectDisconnect(DISCONNECTED)+logout+**удаление данных TDLib** (:42-48); `create` (:50-81, требует phone, `channelStatus.beginConnecting`); `disable` (:91-95 → DISABLED + stop); `enable` (:97-99 → create); `restart` (:102-132, **в main не вызывается**) | `persistence.entity.ChannelAccountEntity` (:9), `ChannelAccountRepository` (:10), `service.ChannelStatusService` (:12) |
| `TelegramAccountProfileSynchronizer` (@Service) | пишет GetMe в канал-аккаунт: externalId (long→String :46), username, phone, displayName, avatarUrl | `ChannelAccountEntity` (:4), `ChannelAccountRepository` (:5, findById/save :39-64) |

### 2.4 `client/`

| Класс | Назначение | Обратные зависимости |
|---|---|---|
| `TelegramClientContext` (record) | channelAccountId + дочерний `ConfigurableApplicationContext` + `TelegramClient` + `TelegramAuthorizationManager` | нет (:9-14) |
| `TelegramClientManager` | `Map<UUID, TelegramClientContext>` в памяти процесса; create/require/stop/restart/disconnect (logout :129)/closeAll (:106-114) | нет серверных; **`closeAll()` никем в main не вызывается** (см. §8) |
| `TelegramClientLifecycleService` (@Service) | фасад над manager | нет (:8-59) |
| `TelegramContextFactory` (587 стр.) | **фабрика дочерних Spring-контекстов на аккаунт**: MapPropertySource `spring.telegram.client.*` (:34-35, :415-518), пути `<databaseDirectory>/<uuid>/{database,files}` (:419-445), регистрация слушателей TDLib (:167-300), прокси (:520-586, **дублирующийся блок socks5 :568-578**) | `IPlatformMessagePublisher`+`KafkaPlatformMessagePublisher` (:6-7), `ChannelAccountEntity`/`ChannelAccountRepository` (:8-9), `ConversationRepository` (:10), `MessageRepository` (:11), `MessageService` (:12), `IAttachmentStorage` (:13), `ChannelStatusService` (:25) — все захватываются в создаваемые слушатели (:302-413) |
| `TelegramInboundMessageListener` | `UpdateNewMessage` → processor → `platformMessagePublisher.publish(withOrigin(LIVE))` (:101-113); фильтрация исходящих с sendingState и не-`MessageSenderUser` | `kafka.producer.IPlatformMessagePublisher` (:3), `common.notification.EventOrigin` (:112) |
| `TelegramInboundMessageProcessor` | `TdApi.Message` → `PlatformMessageRequest`; TEXT/IMAGE/AUDIO; unsupported → caption + metadata (:283-315, хардкод `"Теперь в TG!"` :311); скачивание файлов в `attachmentStorage` (:448-548, имена `telegram-<id>.jpg` :387); `cleanupAttachments` (:550-577) | `server.storage.IAttachmentStorage`/`StoredAttachmentMetadata` (:7-8) |
| `TelegramMessageReadListener` | `UpdateChatReadOutbox` → поиск conversation в обход SPI → `messageService.markReadUpTo(conversationId, long)` | `persistence.entity.ConversationEntity` (:3), `ConversationRepository` (:4, :58-64), `service.MessageService` (:5, :78-81) — **прямой long watermark** |
| `TelegramMessageSendListener` (внешний + `Succeeded`/`Failed`) | `UpdateMessageSendSucceeded/Failed` → `messageService.recordExternalOutcome(accountId, chatId, oldId, newId, failed)` (:33-34, :55-56); статический `deleteTemporaryFiles` по префиксу в `java.io.tmpdir` (:67-79) | `persistence.repository.MessageRepository` (:3 — **принимается в конструктор, но не используется**), `service.MessageService` (:4) |
| `TelegramAuthorizationStateListener` | `UpdateAuthorizationState` → `ChannelStatusService.transition` (:179-198) + `fireChannelChanged(new ChannelEvent(...))` (:185) + GetMe→profileSynchronizer (:200-262) | `service.ChannelStatusService` (:19); `AbstractEventDispatcher`/`ChannelEvent`/`IChannelListener` из SPI-пакета (:4-6) |

### 2.5 `config/`

| Класс | Назначение | Обратные зависимости |
|---|---|---|
| `TelegramClientConfiguration` | @Configuration `@Import(TdlibAutoConfiguration)` (:7-8) — используется только внутри дочерних контекстов; из основного приложения **исключён** (ServerApplication :21-27) | `kit.penny.tdlib.TdlibAutoConfiguration` (:3) |
| `TelegramConnectorConfiguration` (@Configuration, component-scan'ится приложением) | бины `telegramContextFactory` и `telegramClientManager` | в конструктор фабрики инжектируются `IPlatformMessagePublisher`, `ChannelAccountRepository`, `ConversationRepository`, `MessageRepository`, `MessageService`, `IAttachmentStorage`, `ChannelStatusService`, `List<IChannelListener>` (:7-17, :23-48) — **главный узел склейки Telegram↔server** |
| `TelegramPropertiesConfiguration` | `@EnableConfigurationProperties(TelegramProperties.class)` (:7-9) — свойства TDLib-starter включены в общий bootstrap | `kit.penny.tdlib.properties.TelegramProperties` (:3) |

### 2.6 `startup/` и `storage/`

| Класс | Назначение | Обратные зависимости |
|---|---|---|
| `TelegramClientStartupService` (@Service) | `@EventListener(ApplicationStartedEvent)` `restoreTelegramClients()` (:37-58): `findAllByChannelTypeAndChannelStatusIn(ChannelType.TELEGRAM, [CONNECTED, CONNECTING])` (:42-48) → `create`; ошибка → `transition(ERROR,"CONNECTOR_RESTORE_FAILED")` (:78) | `ChannelAccountEntity` (:6), `ChannelAccountRepository` (:7), `service.ChannelStatusService` (:14) |
| `TelegramDataStorage` (@Component) | `delete(channelAccountId)` — рекурсивное удаление `<databaseDirectory>/<uuid>` (:26-74) | только `TelegramProperties` (:3) |

### 2.7 Связанные классы вне пакета telegram

- `ServerApplication` — импорты `TelegramClientConfiguration`, `TdlibAutoConfiguration`;
  `@SpringBootApplication(exclude={UserDetailsServiceAutoConfiguration, TdlibAutoConfiguration})`;
  `@ComponentScan(excludeFilters=TelegramClientConfiguration)` — `ServerApplication.java:3-4, 15-28`.
- `service.MessageService.markReadUpTo(...)` — telegram-семантика в ядре (:1125-1195), единственный
  вызывающий — `TelegramMessageReadListener:78`.
- `service.MessageService.recordExternalOutcome(...)` (:136-141) — вызывается только telegram-listener'ами.
- `service.ChannelAttachment` — DTO команды Send (:7-19 пакета `server.service`).
- `service.ChannelStatusService` — машина статусов канала (beginConnecting :26-35,
  expectDisconnect :38-50, transition :52-74); используется telegram-listener'ом и account-сервисом.
- `kafka.routing.KafkaTopicNames` — per-channel топики (§5).
- `mapper.OutboundMessageKafkaCommandMapper` — Kafka DTO → `SendMessageCommand`, грузит вложения
  из `IAttachmentStorage` (:14, :44-58) — мост ядро→SPI.

---

## 3. Просачивание Telegram в ядро (вне `connector/telegram`)

Прямых `ChannelType.TELEGRAM` вне telegram-пакета — **2 места**:

1. `service/RecentChatsSyncScheduler.java:42-48` — `@Scheduled` метод
   `synchronizeConnectedTelegramChannels()` выбирает `findAllByChannelTypeAndChannelStatus(
   ChannelType.TELEGRAM, CONNECTED)` (интервал `clientbus.sync.recent-chats.interval`, application.yml:41-44).
2. `ServerApplication` — импорты и исключения telegram-конфигураций (см. §2.7).

Семантическое просачивание без слова «TELEGRAM»:

3. `service/MessageService.markReadUpTo` (:1129-1195) — javadoc «Marks all **Telegram** outbound
   messages…»; разбор `externalId` как `long` (:1165-1177) и сравнение с Telegram read-watermark.
   Числовой `long` параметр в сигнатуре публичного API ядра.
4. `connector/command/SendMessageCommand.java:4` — команда SPI импортирует `server.service.ChannelAttachment`.
5. `connector/ChannelEventHandler.java:4` и `connector/ChannelSynchronizationListener.java:4` — базовый SPI-пакет зависит от `server.service.*`.
6. SyncHistory: `notification/history/HistorySyncService.execution(...)` (:206-214) возвращает
   `Execution(ChannelType type, String externalId)` из conversation — тип канала берётся из БД (нейтрально),
   но worker (`HistoryOperationWorker.java:92-97`) — потребитель коннектора; `HistorySyncCommand` (server.notification.contract) живёт в server.
7. `service/ConversationService.markAsRead` (:1160-1204) — публикует `ChannelReadKafkaCommand` на per-channel
   топик (нейтрально по типу, но завязан на строковый externalId из канала).
8. `service/MessageProcessingService.processPlatformMessage` (:93-96) — направление сообщения =
   совпадение `senderExternalId` со `channelAccount.externalId` (в Telegram — числовой user ID,
   пишется `TelegramAccountProfileSynchronizer:46`).
9. `clientbus.kafka.outbound.enabled-channels` (application.yml:64-69) — список каналов, **включая
   WHATSAPP и VK, для которых коннекторов нет**; топики создаются, `getConnector` бросит исключение при
   попадании сообщения.
10. `springdoc.packages-to-scan` (application.yml:117-119) — в общий конфиг вписан пакет
    `kit.penny.clientbus.server.connector.telegram.authorization`.
11. Итого grep `TELEGRAM` по `server/src/main`: 8 совпадений — 2 в ядре (§3.1-3.2), 6 внутри
    telegram-пакета (`TelegramChannelConnector:84,1001`, `TelegramClientStartupService:43`,
    `TelegramChannelAccountService:39`, `TelegramAuthorizationService:148,170,181`).
    Ветвлений по другим каналам (`WHATSAPP/VK/DIKIDI/...`) в main-коде нет вообще.

---

## 4. Конфигурация

### 4.1 Свойства (application.yml, `server/src/main/resources/application.yml`)

Блок TDLib `spring.telegram.client.*` (:200-224), читается `TelegramProperties` из starter
(`kit.penny.tdlib.properties.TelegramProperties`, включён `TelegramPropertiesConfiguration`):
- `api-id: 33073180` (:206), `api-hash: af4cc39ef18fb85d54c2cef445627f97` (:207) — **захардкожены
  в yml, закомментированы альтернативы**; env-переменных нет.
- `database-directory: ./data/telegram` (:211) — относительно CWD процесса; в репозитории существует
  `data/telegram/<uuid>/` (сессии).
- `database-encryption-key: TELEGRAM_DATABASE_SECRET` (:212) — константа в yml.
- `use-test-dc/use-file-database/use-chat-info-database/use-message-database/use-secret-chats` (:214-218),
  `system-language-code/device-model/system-version/application-version/log-verbosity-level` (:220-224).
- `proxy.*` (server/port/http/socks5/mtproto) — поддерживаются `TelegramProperties`/
  `TelegramContextFactory.addProxyProperties` (TelegramContextFactory.java:520-586), в yml не заданы.
- `phone` — per-account, приходит из БД (ChannelAccount.phone), подставляется в дочерний контекст
  (TelegramContextFactory.java:482-485).

Прочие ключи:
- `clientbus.sync.recent-chats.interval|lease` (:41-44; `RecentChatsSyncProperties`).
- `clientbus.kafka.outbound.enabled-channels` (:64-69) — через `@Value` в
  `kafka/config/KafkaTopicConfiguration.java:21-27`.
- `clientbus.storage.local-path: D:\clientbus\storage` (:61-62) — `StorageProperties`
  (`config/properties/StorageProperties.java:5`) + `@Value` в `storage/LocalIAttachmentStorage.java:19`.
- `spring.kafka.consumer.*-group-id` (:164-171) — группы connector-command/outbound/channel-read/history.
- `springdoc.packages-to-scan` (:117-119) — содержит `connector.telegram.authorization`.
- Тестовый `server/src/test/resources/application-test.yml`: `enabled-channels: [TELEGRAM]` (:62-65);
  `spring.telegram.client.*` **не переопределены** (дефолты `TelegramProperties`).
- `@ConfigurationProperties`-классы: `StorageProperties`, `RecentChatsSyncProperties`,
  `NotificationProperties`, `BootstrapAdminProperties`, `ManagementAccessProperties`,
  + внешний `TelegramProperties` (starter).

### 4.2 Maven (`server/pom.xml`)

- Репозиторий TDLib: `github` → `https://maven.pkg.github.com/grushaju/tdlib` (:33-38) — требует
  GitHub-credentials.
- Зависимость: `kit.penny:spring-boot-starter-tdlib:1.0.3` (:223-227).
- Native library path для тестов: surefire `<argLine>-Djava.library.path="${project.basedir}/libs/windows_x64/"</argLine>`
  (:232-239); DLL: `server/libs/windows_x64/{tdjson.dll, tdjni.dll, libcrypto-3-x64.dll, libssl-3-x64.dll, zlib1.dll}`.
  Путь **Windows-специфичен**; прод-запуск рассчитывает на библиотеки рядом с процессом.

### 4.3 Env-переменные

`TELEGRAM_*`/`TDLIB_*` env-переменных **не найдено** ни в yml, ни в scripts, ни в документации.
Общие env (`CLIENTBUS_*`, `KAFKA_BOOTSTRAP_SERVERS`) к Telegram отношения не имеют.
Апи-ключи Telegram живут только в application.yml (риск, см. §10).

---

## 5. Kafka

### 5.1 Топики (`kafka/routing/KafkaTopicNames.java`)

| Топик | Назначение | file:line |
|---|---|---|
| `clientbus.platform-messages` | входящие `PlatformMessageRequest` (из telegram-inbound и history-import) | :30-32 |
| `clientbus.platform-conversations` | `PlatformConversationRequest` (recent chats) | :34-36 |
| `clientbus.platform-events` | `PlatformMessageKafkaEvent` (состояния сообщений) | :38-40 |
| `clientbus.outbound.<channelType>` | исходящие команды (`clientbus.outbound.telegram`) | :101-112 |
| `clientbus.channel-read.<channelType>` | read-команды | :42-53 |
| `clientbus.channel-command.recent-chats.<channelType>` | синк чатов | :160-171 |
| `clientbus.channel-command.conversation-history.<channelType>` | синк истории | :223-234 |
| `clientbus.conversation-history-result` | синхронный результат history | :24-26, :286-288 |
| `clientbus.history-sync-operations` (+`.dlq`) | durable history-операции stage 11 | notification/history/HistorySyncConfiguration.java:12, 27-28 |
| `*.dlq` для каждого | DLQ | `dlq()` :290-300 |

Создание пер-канал-топиков — по `clientbus.kafka.outbound.enabled-channels`
(`kafka/config/KafkaTopicConfiguration.java:89-297`).

### 5.2 DTO и (де)сериализация

- Конверт `KafkaEvent<T>` (common.kafka). Producer — `JacksonJsonSerializer`
  (application.yml:160-162); кастомные `KafkaEventJsonSerializer/Deserializer`
  (kafka/config) — десериализатор выбирает payload-класс по `eventType`
  (KafkaEventJsonDeserializer.java:100-152); `HistoryCommandPublisher` — StringSerializer + ручной JSON
  (notification/history/HistoryCommandPublisher.java:17-18).
- Исходящие: `OutboundMessageKafkaCommand` (common/kafka) → публикует `KafkaOutboundMessagePublisher`
  (server, тип канала из conversation: `MessageProcessingService.java:275,307`); consumes
  `KafkaOutboundMessageConsumer`; маппинг в `SendMessageCommand` —
  `mapper/OutboundMessageKafkaCommandMapper.java:22-58`.
- Входящие: `PlatformMessageRequest` (common/dto/message) публикует
  `kafka/producer/KafkaPlatformMessagePublisher.java:27-52` (это делает **telegram- listener через
  общий порт `IPlatformMessagePublisher`**); consume `KafkaPlatformMessageConsumer` →
  `MessageProcessingService`. События: `PlatformMessageEvent` (common) / `PlatformMessageKafkaEvent` —
  consumer `KafkaPlatformEventConsumer.java:31-54`; **издатель `IPlatformEventPublisher` в main не
  инжектируется нигде** (латентный путь).
- History: `SyncRecentChatsKafkaCommand`, `SyncConversationHistoryKafkaCommand` (+`.Result`),
  `ChannelReadKafkaCommand` (common/kafka); durable `HistorySyncCommand` —
  `server/notification/contract/HistorySyncCommand.java` (живёт в server!).

### 5.3 Где externalId трактуется числом

- `TelegramChannelConnector.parseChatId/parseMessageId` (:1088-1118) и `Long.parseLong(command.externalId())` (:580-587).
- `MessageService.markReadUpTo` (:1165-1177) — ядро парсит `message.externalId` в `long`.
- `TelegramMessageReadListener:55-56` (`Long.toString(chatId)` как clientExternalId),
  `TelegramMessageSendListener:33-34,55-56` (`Long.toString(...)` correlation).
- `TelegramAccountProfileSynchronizer:46` (`Long.toString(user.id)` → channelAccount.externalId);
  `TelegramInboundMessageProcessor:331,353,355`, `TelegramConversationMapper:40` (user/chat/message id → String);
  `TelegramClientAccountDiscovery:57`.
- Ядро больше нигде `parseLong` по externalId не делает (проверено grep — остальные `Long.parseLong` в CRM/Push не про externalId).

---

## 6. Хранилище данных

| Что | Путь/механизм | Кто создаёт | Кто чистит | Конфигурируемо |
|---|---|---|---|---|
| Сессии TDLib ( БД + files) на аккаунт | `<spring.telegram.client.database-directory>/<channelAccountId>/{database,files}` — TelegramContextFactory.java:419-445; фактически `data/telegram/<uuid>/` (есть в репозитории) | TDLib/starter при старте клиента | `TelegramDataStorage.delete(channelAccountId)` (storage/TelegramDataStorage.java:26-74) — **только** при disconnect (TelegramChannelAccountService:47). При disable/enable и удалении process-уровня не трогается | Да (`database-directory`) |
| Персистентные вложения/аватары | `clientbus.storage.local-path` = `D:\clientbus\storage`, ключ `xx/yy/<uuid>.ext` — storage/LocalIAttachmentStorage.java:183-215 | Telegram: аватары собеседников (`telegram-avatar-*.jpg`, TelegramChannelConnector:716-724), аватар канала (profile sync), входящие медиа (`telegram-<id>.jpg`, TelegramInboundMessageProcessor:387, 506-548); ядро: outbound-вложения, аватары из OutboundConversationService:69 | Inbound cleanup `cleanupAttachments` (:550-577); outbound-аватар — OutboundConversationService (:87-90); **аватары каналов/клиентов при disconnect/deleteChannel не удаляются** (осиротевшие объекты) | Да (`clientbus.storage.local-path`) |
| Временные файлы исходящих | `java.io.tmpdir`, префикс `clientbus-telegram-<caid>-<messageId>-<attemptId>-<suffix>` — TelegramChannelConnector:1007-1026 | `sendWithTemporaryFile` (:915-996) | (а) сразу при IOException/RuntimeException (:977-995); (б) `deleteTemporaryFiles` по префиксу — TelegramMessageSendListener.java:67-79, вызов из `@TransactionalEventListener(AFTER_COMMIT) releaseCompletedAttempt` (TelegramChannelConnector.java:998-1005) на событие `OutboundSendCompleted`, публикуемое `MessageService.applyExternalResult` (MessageService.java:154-157) | **Нет** (фиксированный java.io.tmpdir) |
| TDLib download cache | внутри `<...>/files` того же аккаунт-каталога | TDLib | вместе с каталогом | да (files-directory) |

---

## 7. Тесты

### 7.1 Специализированные Telegram-тесты (17)

Unit (Mockito, без контекста):
- `connector/telegram/TelegramChannelConnectorTest.java` — все команды коннектора: send text/image/audio,
  temp-файлы (существование/удаление), markRead (TdApi.ViewMessages), recent chats, история (курсор,
  retry, границы TDLib, `CreatePrivateChat`), discover/avatar лимиты, `SyncClientAccountCommand`.
- `connector/telegram/TelegramClientAccountDiscoveryTest.java` — поиск по phone/username, preview/avatar
  лимиты и таймауты, отсутствие утечки phone в ошибках.
- `connector/telegram/TelegramConversationMapperTest.java` — маппинг TdApi→PlatformConversationRequest.
- `connector/telegram/account/TelegramChannelAccountServiceTest.java` — порядок expectDisconnect→stop,
  enable через общий create, restart инциденты (строки 27-45).
- `connector/telegram/authorization/TelegramAuthorizationServiceTest.java` — маппинг всех TDLib статусов,
  submit code/password/email, find() через registry + ACL.
- `connector/telegram/authorization/TelegramAuthorizationControllerTest.java` — **REST /telegram/***:
  делегирование всех 9 ручек + проверка `accessPolicy.require` до коннектора (:59-70).
  ⚠ Unit-директ (не MockMvc/@WebMvcTest) — HTTP-слой маршрутов `/api/channels/{id}/telegram/*`
  отдельными интеграционными тестами **не покрыт**.
- `connector/telegram/client/TelegramAuthorizationStateListenerTest.java` — статус-маппинг, GetMe→profile.
- `connector/telegram/client/TelegramContextFactoryTest.java` — per-account свойства/пути, регистрация bean'ов слушателей.
- `connector/telegram/client/TelegramInboundMessageListenerTest.java` — фильтрация исходящих/sender.
- `connector/telegram/client/TelegramInboundMessageProcessorTest.java` — текст/фото/аудио, bot skip, не-private skip, attachment storage.
- `connector/telegram/client/TelegramMessageReadListenerTest.java` — lookup conversation + `markReadUpTo`.
- `connector/telegram/client/TelegramMessageSendListenerTest.java` — Succeeded/Failed → `recordExternalOutcome`, temp-файлы.
- `connector/telegram/client/TelegramClientManagerTest.java` — create/stop/disconnect/closeAll.
- `connector/telegram/startup/TelegramClientStartupServiceTest.java` — восстановление CONNECTED/CONNECTING, ERROR при провале.
- `connector/telegram/storage/TelegramDataStorageTest.java` — рекурсивное удаление каталога.

Интеграционные (PostgreSQL/Kafka Testcontainers, `@SpringBootTest`+profile `test`):
- `integration/TelegramOutboundMessageFlowIntegrationTest.java` (2151 стр.) — сквозной Kafka→consumer→
  реальный `TelegramChannelConnector` (мок `TelegramClientManager`/`TelegramClient`): text/image, FAILED,
  retry с новым Telegram message id, temp-файлы до/после commit (:49-127 setup).
- `connector/telegram/client/TelegramMessageReadListenerIntegrationTest.java` — UpdateChatReadOutbox →
  реальные `MessageService.markReadUpTo`/БД (:71+).

### 7.2 Остальные затронутые тесты

Всего **68** файлов ссылаются на `Telegram|TELEGRAM` (полный список в
`grep`-выгрузке; ключевые группы): Kafka outbound/read/recent-chats/history consumers & producers
(топики `.telegram`), `HistoryOperationWorkerTest`/`HistorySyncIntegrationTest`/`HistoryWireProtocolTest`
(connector mock для history), `ChannelServiceTest`/`ChannelServiceIntegrationTest` (lifecycle registry),
`OutboundConversationAvatarIntegrationTest`, `NewConversationAvatarBrowserAcceptance` (browser, mock
registry.getConnector(TELEGRAM) :75), `MessageServiceTest`, `MessageProcessingService*`,
`ClientAccountRepositoryTest` и пр.
Browser-приёмка (Java-обвязка + Playwright-спеки в `ui/e2e`): `NewConversationAvatarBrowserAcceptance.java`,
`InboxBrowserAcceptance.java`, `ui/e2e/fixtures/inbox.ts:21,30` (фикстуры `TELEGRAM`),
`ui/e2e/new-conversation-avatar/avatar.spec.ts:103`, `ui/e2e/history/history.spec.ts` (через фикстуры).
`notification/acceptance/NotificationAcceptanceEnvironment.java:38,221` — **исключает
`TdlibAutoConfiguration`** из acceptance-контекста (Telegram там на тестовом коннекторе).
Отдельных тестов HTTP-маршрутов `/telegram/*` поверх controller-unit — **нет**.

---

## 8. Жизненный цикл

| Сценарий | Где | Механика | Отличия |
|---|---|---|---|
| Перезапуск процесса / startup-восстановление | `telegram/startup/TelegramClientStartupService.java:37-58` | `ApplicationStartedEvent` → аккаунты TELEGRAM со статусом CONNECTED/CONNECTING → `create()`; провал → `ERROR/CONNECTOR_RESTORE_FAILED` | Сессии TDLib подхватываются с диска; авторизация обычно уже READY → `AuthorizationStateReady` → CONNECTED |
| Первый connect / enable | `TelegramChannelAccountService.create` (:50-81) / controller `/enable` (TelegramAuthorizationController:88) → `enable` (:97-99) | phone обязателен, `beginConnecting`, старт клиента; статусы через `AuthorizationStateListener.mapStatus` (:152-177): Wait* → CONNECTING, Ready → CONNECTED (+GetMe→profile), LoggingOut/Closing/Closed → DISCONNECTED | enable не трогает данные — повторная авторизация возможна |
| disable | controller `/disable` (:80) → `TelegramChannelAccountService.disable` (:91-95) | `expectDisconnect(DISABLED)` (REQUIRES_NEW, ChannelStatusService:38-50) → `stop` (закрытие дочернего контекста, без logout) | Сессия **сохраняется**; статус DISABLED не порождает инцидент-уведомление |
| disconnect / logout | controller `/disconnect` (:96) → `TelegramChannelAccountService.disconnect` (:43-48) | `expectDisconnect(DISCONNECTED)` → `manager.disconnect` (**`telegramClient.logout()`** — TelegramClientManager:116-151) → `dataStorage.delete` (**удаление сессий TDLib**) | Единственный сценарий, удаляющий данные аккаунта |
| удаление канала | REST `DELETE /api/channels/{id}` (ChannelController:117) → `ChannelService.deleteChannel` (:187-208) | `expectDisconnect(DISCONNECTED)` → `ChannelAccountLifecycleRegistry.getLifecycle(type).disconnect(...)` → `channelRepository.delete` | Тот же disconnect-путь lifecycle + каскадное удаление БД |
| удаление channel-account | REST `PUT /{channelId}/account`... фактический путь — `ChannelService.disconnectChannelAccount` (:255-277) | lifecycle disconnect + `channelAccountRepository.delete(account)` | Отличие от deleteChannel: удаляется только account |
| restart | `TelegramChannelAccountService.restart` (:102-132) | stop+create с новым контекстом | **Публичных вызовов в main не найдено** (только тесты) |
| Остановка процесса (shutdown) | `TelegramClientManager.closeAll()` (:106-114) — **не вызывается из main** (нет @PreDestroy/destroy-метода, grep) | — | ⚠ Дочерние контексты/TDLib-потоки при graceful shutdown явно не закрываются; риск только-записанных сессий |
| Очистка temp-файлов после commit | `MessageService.applyExternalResult` → `OutboundSendCompleted` (:154-157) → `TelegramChannelConnector.releaseCompletedAttempt` (AFTER_COMMIT, :998-1005) | очистка по `channelType==TELEGRAM` (:1001) | Единственный «пост-серверный» hook коннектора; ядро знает enum-тип канала |

---

## 9. UI (точки, предполагающие Telegram)

- `ui/src/api/telegramChannelApi.ts` — весь клиент `/api/channels/{id}/telegram/*`: start :8-12,
  status :19-23, code :29-34, password :42-47, email :55-60, disable :68-72, enable :79-83,
  find :90-119 (no-store).
- `ui/src/components/channels/channelTypeConfig.ts:37-72` — единственный спец-конфиг
  `telegramConfig` с `authorization: 'TELEGRAM'` (:60); остальные каналы → `authorization: 'NONE'` (:63-66).
- `ui/src/components/channels/ChannelConnectionDialog.tsx:10-11, 34-36` — `case 'TELEGRAM'` →
  `TelegramConnectionFlow`; иных сценариев авторизации нет.
- `ui/src/components/channels/TelegramConnectionFlow.tsx` — вся файл (state machine
  WAIT_PHONE_NUMBER/CODE/PASSWORD/EMAIL, :202+, ошибки).
- `ui/src/components/conversations/NewConversationDialog.tsx:13-14, 78-82` (фильтр
  `channel.type === 'TELEGRAM'`), :93, :122, :159-178 (дефолтный telegram-канал), :227, :255
  (`findTelegramClientAccount`), :390-408 (текст «нет Telegram-каналов»), :445, :590 — **UI нового
  диалога работает только с Telegram**.
- `ui/src/pages/ChannelsPage.tsx:16-18, 237, 272` — disable/enable канала напрямую через
  `disableTelegramChannel/enableTelegramChannel` (TelegramEndpoints для всех каналов).
- `ui/src/api/types/channel.ts:5, 11, 23, 94` — `'TELEGRAM'`, `'TELEGRAM_BOT'`,
  `TelegramAuthorizationStatus`.
- `ui/src/components/channels/CreateChannelDialog.tsx:38-39, 62-63, 77, 83, 95, 119, 336, 482-486` —
  выбор типа + подсказка про авторизацию Telegram.
- `ui/src/components/channels/channelLabels.ts:10-11, 28-29`;
  `ui/src/components/common/platform/platform.ts:17-24`;
  `ui/src/components/common/platform/PlatformIcon.tsx:24-25` (telegram.svg).
- e2e: `ui/e2e/fixtures/inbox.ts:21, 30`; `ui/e2e/new-conversation-avatar/avatar.spec.ts:103`.

SPA-маршрутов `/telegram*` в UI **нет** — «/telegram/*» присутствуют только как сегменты REST-API
(`/api/channels/{channelAccountId}/telegram/*`), это и есть «совместимые маршруты», о которых говорит
`Stage 13.txt:157` («платформенный web-адаптер прежних /telegram/* маршрутов»).

---

## 10. Сюрпризы и риски (неочевидное предположение Telegram)

1. **`data/telegram` лежит в корне репозитория** (`./data/telegram` от CWD) — реальные сессии
   аккаунтов живут рядом с исходниками; `database-encryption-key` — константа в yml
   (application.yml:211-212), `api-id/api-hash` тоже захардкожены (:206-207).
2. **`TelegramAuthorizationService`/Controller живут внутри telegram-пакета, но используют
   `CurrentUserService`, JPA-репозитории и `HttpAccessPolicy`** — после выноса в SPI-модуль
   это запрещённые импорты; контроллер сам делает ACL (TelegramAuthorizationController.java:32 и др.).
3. **`ServerApplication` управляет telegram-конфигурацией** (exclude + component-scan exclude) —
   запуск server без Telegram невозможен без правки общего bootstrap (ServerApplication.java:15-28).
4. **`TelegramContextFactory` строит дочерние Spring-контексты, захватывая 7 серверных бинов**
   (репозитории, MessageService, IAttachmentStorage, Kafka publisher) — скрытая «тень» server-контекста
   в каждом аккаунт-клиенте (TelegramContextFactory.java:302-413).
5. **`closeAll()` у `TelegramClientManager` не вызывается при остановке приложения** — TDLib-клиенты
   переживают контекст только «естественной смертью» JVM (grep: вызов лишь в тесте).
6. **Числовой watermark read-статусов протёк в ядро**: публичный `MessageService.markReadUpTo(UUID, long)`
   и `Long.parseLong(externalId)` внутри (MessageService.java:1129-1195) — единственные пользователи —
   Telegram-слушатели.
7. `SendMessageCommand` (будущий SPI) зависит от `server.service.ChannelAttachment` (SendMessageCommand.java:4).
8. Даже базовый SPI-пакет нечист: `ChannelEventHandler → IMessageProcessingService`,
   `ChannelSynchronizationListener → RecentChatsSyncCoordinator`.
9. **enabled-channels содержит WHATSAPP и VK без коннекторов** (application.yml:66-69) —
   топики создаются, сообщения упрутся в `IllegalStateException` реестра.
10. `springdoc.packages-to-scan` в общих ресурсах ссылается на пакет telegram
    (application.yml:119).
11. Мёртвые элементы SPI: `handle(SyncClientAccountCommand)` не вызывается нигде;
    `IChannelEventHandler/ChannelEventHandler` не востребованы; `IPlatformEventPublisher` не
    инжектируется; `TelegramChannelAccountService.restart` без callers; неиспользуемый параметр
    `MessageRepository` в `TelegramMessageSendListener.Succeeded/Failed` (:24, :46).
12. Костыль маппинга неподдерживаемых контентов: caption-заглушки и `"Теперь в TG!"` + JSON-metadata
    `{"unsupportedContentType":...}` (TelegramInboundMessageProcessor.java:283-315).
13. Duplicate-блок `proxy.socks5` в `addProxyProperties` (TelegramContextFactory.java:555-578) —
    косметический баг, стоит починить при переносе.
14. `TestDataFactory` по умолчанию создаёт TELEGRAM-каналы (:79, :93, :152) — сотни тестов неявно
    telegram-зависимы (68 файлов с упоминанием).
15. Аватары каналов/собеседников, сохранённые Telegram в `IAttachmentStorage`, **не удаляются** при
    disconnect/deleteChannel (только сами TDLib-сессии) — мусорные объекты после выноса.
16. `UI`: `ChannelsPage.handleDisable/handleEnable` вызывает telegram-эндпоинты для любого канала
    (ChannelsPage.tsx:237, 272) — предполагает, что каждый канал поддерживает `/telegram/disable|enable`.
17. Дубликат №2 к п.3: `TelegramPropertiesConfiguration` включает `TelegramProperties` глобально —
    без telegram-модуля эти @ConfigurationProperties останутся в bootstrap.

## 11. Чего не найдено (честно)

- Env-переменных `TELEGRAM_*`/`TDLIB_*` — нет ни в конфиге, ни в scripts, ни в CI-файлах.
- HTTP-совместимых маршрутов уровня `/telegram/*` без `/api/channels/...` — нет; «прежние
  /telegram/*» (Stage 13.txt:157) = текущий базовый путь `TelegramAuthorizationController` (`:13`).
- `MockMvc`/`@WebMvcTest` тестов самих URL-маршрутов `/telegram/*` — нет (только unit-тест контроллера).
- Отдельных Telegram-ключей в `server/src/test/resources` — нет (дефолты starter-свойств).
- `TdlibAutoConfiguration`/`TelegramProperties` исходников — внешний артефакт
  `kit.penny:spring-boot-starter-tdlib:1.0.3` (GitHub Packages), в репозитории отсутствует.
- DIKIDI-коннектора как `IChannelConnector` — нет (crm-dikidi не реализует SPI).
