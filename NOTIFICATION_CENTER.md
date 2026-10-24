# Центр уведомлений — этап 11.8

Семантика и публичные DTO зафиксированы в [NOTIFICATION_SEMANTICS.md](NOTIFICATION_SEMANTICS.md) и [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md). Этот файл описывает реализацию центра и её проверку; мобильный transport описан в [MOBILE_PUSH_INTEGRATION.md](MOBILE_PUSH_INTEGRATION.md).

Статус 23.10.2026: **11.8 закрыт по реализации и автоматической приёмке**, включая исправления ревью, регрессию входа и физические имена БД без подчёркиваний.

| Финальная проверка | Результат |
|---|---|
| Полный BE-набор | 776/776; в составе 16 schema tests и 22 push-интеграции |
| Полный UI-набор | 289/289; заключительный повтор — `server/target/notification-11-8-final-ui.log` |
| Полная браузерная регрессия на финальной схеме | 10/10 desktop/mobile: восемь Inbox/SSE и два центра; `server/target/notification-11-8-final-browser.log`, BUILD SUCCESS 21:50:44 +03:00 |
| TypeScript | Приложение, Vite config и E2E прошли заключительную проверку |
| Production UI | Standalone Vite build повторно прошёл; все 45 файлов ui/dist побайтно совпадают с server/target/classes/static, на которых выполнена приёмка |

Код готов к пользовательскому коммиту 11.8. Staging и коммитов нет; пользовательский buildinfo сохранён. Перед применением к существующей локальной БД требуется разовый rename старых таблиц, колонок, индексов и ограничений: Hibernate update не выполняет такое переименование. Пользовательская БД в ходе разработки не изменялась. План и согласование 11.9 выполняются отдельно; реальная FCM/APNs/native-интеграция следует MOBILE_PUSH_INTEGRATION.md.

## Реализация

`PersonalNotificationWriter` вызывается внутри бизнес-транзакции (`MANDATORY`). Он сериализует первое допустимое LIVE-возникновение с доменной строкой, фиксирует первоначальную аудиторию (включая пустую), occurrence/dedup, отдельные записи получателей, delivery intents и персональные outbox-сигналы. Конфликт бизнес-ключа не пересчитывает аудиторию. HISTORY_IMPORT не расходует LIVE decision; слишком старый доверенный источник подавляется по `sourceReplayWindow`.

| Категория | Реальный источник и граница |
|---|---|
| N1 | `ConversationService.changeAssignment`: новый assignmentOccurrenceId только при смене сотрудника, без self/no-op/release alert; прежняя актуальность закрывается в той же транзакции |
| N2 | `KafkaPlatformMessageConsumer → MessageProcessingService`: origin и первоначальное occurredAt/eventId из исходного envelope; legacy envelope сохраняет бизнес-данные без нового alert; runtime N2/v2 включает conversationId для внешних ID, повторяющихся в разных чатах |
| N3 | Persistent sendAttemptId проходит outbound command, IChannelConnector и результат; ручной retry меняет attempt и закрывает прежний alert. Ранний callback фиксируется по account/chat/pending ID и применяется после регистрации; старый attempt не меняет новую отправку |
| N4 | `ChannelStatusService`: lock эффективного состояния, incident ID до восстановления, закрытие на recovery/ожидаемой остановке; намерение пользователя остановить connector фиксируется до внешнего вызова |

Для N1/N3/N4 первоначальное время хранится вместе с устойчивым ID в доменной строке. Повтор после очистки dedup не получает новое время; legacy NULL подавляет новые персональные эффекты. Telegram использует общий IChannelConnector и общую службу состояния, без специальных notification scopes. N5 зарезервирован для отдельно введённой длительной команды; обычная history pagination в 11.10 его не активирует.

Центр и счётчик выбирают текущие доступные записи получателя по общей SQL policy. Утрата доступа скрывает прежнюю запись, но не переписывает факт её создания. Закрытие актуальности alert сохраняет центр и `readAt`. Время создания задаётся PostgreSQL; порядок выдачи — `(createdat, id) DESC`. Cursor привязан к identity/Organization/query и не заменяет ACL.

| REST | Поведение |
|---|---|
| `GET /api/notifications?unreadOnly=false&limit=30&pageCursor=…` | Глобальный список по всем доступным Workspace; limit 1–100 |
| `GET /api/notifications/summary` | Тот же доступ, текущий `unreadCount` |
| `GET /api/notifications/{id}` | Текущий доступный DTO, типизированная цель и вычисленный `alertActive` |
| `PUT /api/notifications/{id}/read` | Идемпотентная отметка одной записи; первый `readAt` сохраняется |
| `PUT /api/notifications/read-all` | Без body, UUID `Idempotency-Key`, сохранённый результат одной глобальной команды |

Read-all выполняет один UPDATE доступных непрочитанных строк и сохраняет результат в той же транзакции. Повтор возвращает прежний результат и не захватывает уведомления, пришедшие позднее. Права проверяются при каждом запросе. Ответы API имеют `Cache-Control: no-store`; ошибки не раскрывают содержимое недоступной цели.

## UI

Кнопка в `AppLayout` открывает центр: список, фильтр непрочитанных, страницы, чтение одной/всех и переход к цели. Для разговора используется `/inbox/{id}`, для аккаунта канала — `/channels?accountId={id}` с открытием его деталей. Перед переходом UI повторно читает DTO, проверяет доступный Workspace, отмечает запись прочитанной и переключает Workspace. Ссылки из `templateArgs` не исполняются.

Список и счётчик используют существующие `UiEventsProvider`, coordinator, `useUiResource` и бюджет фактических GET. Нового polling-таймера нет. Число непрочитанных всегда получается из REST, включая завершение read/read-all; локальная арифметика не используется. Центр глобален и не ограничивается Workspace текущего SSE. При account/scope generation и control-инвалидации чтения/команды отменяются, старые ответы не возвращают частные данные.

Read-all UUID сохраняется до отправки в аккаунтном `sessionStorage` текущей вкладки. При неопределённом результате отдельная кнопка повторяет захваченную исходную команду с тем же ключом; новый явный запуск создаёт новый UUID. Другая вкладка, включая дубликат с первоначальной копией sessionStorage, не заменяет ключ первой. Команда переживает reload/logout в этой вкладке, хранится не дольше семи дней и не повторяется автоматически после истечения окна. Закрытие вкладки завершает жизнь её pending-команды на клиенте; серверный результат сохраняется на весь срок идемпотентности. Ошибка сохранения ключа препятствует отправке команды.

Transient banner появляется только после успешной initial reconciliation и `notifications.created`. UI получает свежий detail через REST и проверяет `createdAt > alertsAfter`, `readAt = null`, `alertActive` и будущий `alertExpiresAt`. Скрытая вкладка не начинает показ. Входящее сообщение уже открытого и видимого диалога не дублируется баннером. Баннер закрывается максимум через десять секунд или раньше по TTL; одновременно сохраняются не более трёх баннеров. `notifications.invalidated` и control-сигналы немедленно очищают баннеры и отменяют их detail-запросы.

Общий для SSE и foreground push dedup хранит только opaque ID/expiry в аккаунтном localStorage: 48 часов, максимум 4096 записей, очистка при logout. Проверка и пометка выполняются до показа. Несколько вкладок и eviction не дают exactly-once. Foreground bridge принимает событие `clientbus:foreground-notification` с `detail.notificationId`; provider SDK в браузер не добавлен.

## Настройки

`clientbus.notifications.creation-enabled`, `sse-enabled` и `push-enabled` по умолчанию выключены. Creation flag влияет на материализацию первоначальной аудитории; уже зафиксированное пустое решение не расширяется после включения. TTL по умолчанию: входящее сообщение — пять минут, назначение/ошибка отправки/инцидент канала — сутки. Retention центра — 30 дней, source replay window — 30 дней, dedup — 90 дней, read-all command — семь дней. Границы валидируются `NotificationProperties`.

## Проверка 23.10.2026

Полный BE-набор: **768/768**, без ошибок и пропусков. Команда: `mvn -o -pl server -am '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-8-full-backend.log test-compile surefire:test`; итоговый BUILD SUCCESS — 12:47:18 +03:00. Включены 24 проверки центра, девять реального N1/N2 pipeline, 11 интеграций send attempt/callback, 16 push foundation, девять реальных N4 lifecycle и существующие проверки сообщений, Inbox, доступа, схемы, outbox и SSE/Kafka.

Общий прогон выявил блокировку Kafka partition при recovery команды уже удалённого сообщения: повторный EntityNotFound не давал отправить исходную команду в DLQ. Теперь только отсутствие цели пропускает отметку FAILED; остальные ошибки сохраняют прежний retry. Отдельная регрессия проверяет DLQ. Проверка отсутствия DLQ после успешной отправки фильтрует текущий messageId и не принимает старые записи соседних сценариев общего брокера за ошибку своей отправки. Дополнительно проверено, что FAILED processing остаётся terminal при позднем внешнем результате. Изолированный повтор ранее упавших интеграций — 45/45; финальный общий прогон — без падений.

UI unit suite: **286/286**, включая 17 новых тестов центра/API/storage/provider. Команда: `cd ui; node --test tests`; лог — `server/target/notification-11-8-ui.log`. Проверены реальные React/provider lifecycle, глобальная выдача и REST count, cursor/filter, read-all retry/new action, поздние GET/PUT, control другого Workspace, eligibility и общий SSE/push dedup, logout и deep link канала. Старый cancellation-тест переведён на управляемое время без ослабления строгой проверки количества запросов; успешный reconnect проверяется также после истечения retry delay.

`npx tsc --project tsconfig.app.json --noEmit --incremental false`, E2E TypeScript и standalone Vite build прошли. Пользовательский `ui/tsconfig.app.tsbuildinfo` сохранён: SHA-256 `FCBC3B3FED3138EC5758BE924FC98F0D6A1E593EB5868D4589203ACD3D617887`.

Реальный browser acceptance прошёл: **10/10 desktop/mobile сценариев** — восемь прежних Inbox/SSE и два новых центра. Команда: `mvn -o -pl server -am '-Dtest=NotificationBrowserAcceptance,NotificationCenterBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-8-browser-regression.log test-compile surefire:test`; BUILD SUCCESS — 12:54:30 +03:00. Сохранён прежний лимит GET в сценарии relay gap с живым SSE heartbeat.

Новый `NotificationCenterBrowserAcceptance` с `playwright.notification-center.config.ts` использует production static UI, проверяет совпадение его index с ui/dist, создаёт отдельные desktop/mobile fixtures и удаляет временный credential manifest в `finally`. Сквозной сценарий: N1 через обычное административное назначение, N2 через реальный processing service с доверенным source context, новый notifications.created на прежнем SSE при другом выбранном Workspace, две сессии одного получателя/чужой получатель, read/read-all, потерянный подтверждённый ответ и повтор прежнего ключа после нового arrival, reload непрочитанной записи без старого баннера, фактический переход в Inbox другого Workspace с общим readAt и отзыв ACL. Проверены viewport диалога, Escape и возврат фокуса; desktop/mobile screenshots просмотрены. Временные manifests удалены, buildinfo сохранён, static bundle совпадает с production build.

Миграций, staging и коммитов в ходе реализации нет.

## Исправление входа при выключенном SSE — 23.10.2026

При `sse-enabled=false` контроллер SSE отсутствовал. Запрос с действующим JWT попадал в обработку отсутствующего ресурса и вторичный ERROR dispatch; защищённый error endpoint превращал ожидаемый 404 в 401. UI корректно реагировал на 401 выходом из сессии, поэтому после входа пользователь возвращался на login. Прежняя browser-проверка выключения SSE возвращала 404 тестовым контроллером и не покрывала отсутствие production endpoint.

Добавлен `UiEventsDisabledController`, активный только при false/отсутствующем флаге. Он отвечает аутентифицированному клиенту `404 SSE_DISABLED` с `no-store` без ERROR dispatch. Проверка JWT сохраняется; anonymous/invalid JWT по-прежнему получают 401. Флаги не включаются автоматически, UI использует предусмотренный REST fallback.

Новая HTTP-регрессия сначала воспроизвела **401 вместо 404**, затем прошла для stream без Workspace и с Workspace; последующий summary с тем же JWT остаётся доступным. Новый React-тест проводит настоящий 404 через transport и UiEventsProvider и проверяет сохранение сессии. Проверено после исправления: **34/34 BE** (25 center + девять enabled transport), **287/287 UI**. Логи: `server/target/notification-11-8-disabled-sse-reproduction.log`, `notification-11-8-disabled-sse-fix.log`, `notification-11-8-disabled-sse-ui.log`. Для применения нового Spring controller запущенный backend требуется перезапустить.

## Закрытие завершающего ревью — 23.10.2026

Закрыты три замечания к изменениям после коммита `d61e1f5` (11.7):

- Read-all: общий localStorage позволял второй вкладке заменить ключ повтора первой и захватить новое уведомление другим SQL snapshot. Pending-команда перенесена в аккаунтный sessionStorage вкладки; retry использует захваченную исходную команду и проверяет её сохранность/срок. Дублирование вкладки, reload и logout сохраняют безопасную семантику ключа.
- Push deadlock: INVALID_ENDPOINT блокировал delivery до installation, а logout/rebind делали наоборот. Обработка provider result теперь сначала блокирует installation; cleanup также завершает изменения регистраций до блокировок delivery. Постоянные PostgreSQL-тесты останавливают реальную мутацию после захвата installation и проверяют конкурентный результат при logout, rebind и DENIED без искусственного захвата delivery в тесте.
- Ротация во время provider call: версия endpoint проверяется до terminal-перехода. Поздний INVALID_ENDPOINT прежнего token переводит тот же delivery в RETRY на новом endpoint; исходный expiresAt сохраняется. При исчерпании TTL получается EXPIRED, старая claimVersion не меняет последующий retry, новая регистрация остаётся включённой.

До исправлений постоянные регрессии воспроизвели **два падения UI** (17 passed / 2 failed) и **пять BE** (15 passed / 2 failed / 3 errors): подмену ключа, FAILED вместо RETRY и PostgreSQL deadlock. Логи — `server/target/notification-11-8-review-red-ui.log` и `notification-11-8-review-red-backend.log`. Дополнительно закреплены исходный TTL, late claim после retry и retention/FK регистраций при очистке истории delivery.

После исправлений полный BE-набор — **775/775**, failures/errors/skipped = 0: `mvn -o -pl server -am '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-8-review-full-backend.log test-compile surefire:test`; BUILD SUCCESS — 20:03:09 +03:00. Foundation-набор содержит 22 push-интеграции; после уточнения проверки retention/FK он повторно прошёл **22/22** (лог `server/target/notification-11-8-review-final-push.log`, BUILD SUCCESS — 20:11:39 +03:00). Полный UI-набор — **289/289**, без пропусков: `cd ui; node --test tests`; лог — `server/target/notification-11-8-review-ui.log`.

Реальная браузерная регрессия — **10/10 desktop/mobile**: восемь прежних Inbox/SSE и два обновлённых центра. В центре две активные вкладки одного browser context создают разные pending read-all, первая теряет уже подтверждённый сервером ответ, команда второй не доходит до сервера, затем приходит новое уведомление. После reload каждой вкладки повторяются её исходные UUID; повтор первой не отмечает новое уведомление прочитанным. Сохранены проверки двух отдельных сессий, чужого получателя, ACL, REST count и перехода к цели. Команда — `mvn -o -pl server -am '-Dtest=NotificationBrowserAcceptance,NotificationCenterBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-8-review-browser.log test-compile surefire:test`; BUILD SUCCESS — 20:10:45 +03:00.

TypeScript приложения, Vite config и E2E, а также production Vite build прошли. Все 45 файлов ui/dist совпали с server/target/classes/static. Пользовательский buildinfo на начало этого исправления сохранён: SHA-256 `10111806CE40BC7081F993F59149C81D3702706ADBF4FA312797C94957A43508`. Миграций, staging и коммитов нет. Все три замечания закрыты; 11.8 завершён. Следующий 11.9 требует отдельного плана и согласования.

## Имена объектов БД без подчёркиваний — 23.10.2026

По требованию пользователя физические имена новой подсистемы приведены к строчному написанию без подчёркиваний. Обновлены 107 явно заданных имён и все их использования в SQL, ACL, outbox, push, callback-корреляции, тестах и документации. В объём входят девять таблиц: notification, notificationoccurrence, notificationdedup, notificationcommand, notificationoutbox, notificationdelivery, pushinstallation, historysyncoperation и outboundsendcallback. Java-поля и публичные DTO сохраняют camelCase.

Проверяются реальные PostgreSQL table/column/index/constraint names, включая автоматически генерируемые объекты. PK получают явные имена вида `<таблица>pk` через именованный unique(id), который Hibernate использует как PK без дублирующего unique index. `NotificationSchemaNaming`, зарегистрированный стандартным Hibernate Integrator, задаёт имена автоматически создаваемым enum CHECK до экспорта схемы. Тела проверок сохраняются. Проверка каталога сначала выявила автоматические имена с суффиксами pkey/check, после исправлений все имена новых объектов проходят правило.

`NotificationSchemaTest` проверяет все девять таблиц, отсутствие подчёркиваний в колонках/индексах/ограничениях, имена PK и отсутствие дублирующего unique(id). Все **16/16** schema tests прошли, включая создание через Hibernate update, повторный update с сохранением данных и прежние проверки ограничений.

Полный BE-прогон после переименования — **776/776**, failures/errors/skipped = 0: `mvn -o -pl server -am '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-schema-flat-full-backend.log test-compile surefire:test`; BUILD SUCCESS — 20:47:49 +03:00. Реальный центр на новой схеме прошёл **2/2 desktop/mobile** сценария, включая две вкладки, read-all retry/reload, REST count, ACL и переход к цели: `mvn -o -pl server -am '-Dtest=NotificationCenterBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-schema-flat-browser.log test-compile surefire:test`; BUILD SUCCESS — 20:49:54 +03:00. Все 45 production UI resources совпали, пользовательский buildinfo сохранён.

Пользовательская БД не изменялась. Для существующей локальной схемы требуется разовое переименование старых объектов: Hibernate update не переименовывает прежние таблицы и колонки. SQL-миграций, staging и коммитов нет. Номенклатура закреплена в NOTIFICATION_CONTRACTS.md.
