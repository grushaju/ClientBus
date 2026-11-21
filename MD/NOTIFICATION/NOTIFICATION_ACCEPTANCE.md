# Приёмка уведомлений — 11.12

Статус: 11.12 и этап 11 закрыты в согласованном объёме по коду и автоматической приёмке 26.10.2026 после повторного ревью. План принят пользователем вместе с дополнением о звуковом оповещении. Итог основной приёмки: 958 BE, 391 UI, все 54 уникальных функциональных браузерных сценария, сборки и отдельный усиленный нагрузочный профиль прошли; этот Maven-прогон завершён в 22:29:57 MSK. Последующее дополнение «Очистить уведомления» и его отдельные проверки описаны ниже. Слышимость звука на целевом браузере и физическая Android/iOS-приёмка перечислены отдельно ниже. Пользовательские БД, каналы и Kafka topics для приёмки не использовались; миграций, Git staging и коммитов агент не создавал.

## Порядок

1. Сопоставить критерии Stage 11 с существующими тестами и подготовить два полных экземпляра приложения с отдельными HTTP-портами, gateway instance-id и consumer groups, общей тестовой PostgreSQL/Kafka.
2. Дополнить сквозную web-приёмку: fanout, роли/сессии и запреты, центр/read-all, отказ Kafka при живом HTTP, restart экземпляра и восстановление History.
3. Проверить mobile foundation через REST и штатный scheduled worker с тестовым provider; переиспользовать детерминированные проверки CAS/lease/TTL.
4. Выполнить локальный профиль и записать задержки, сходимость состояния, фактические REST-запросы и ресурсы.
5. Завершить BE/UI/Inbox/browser регрессию, production/rollback-сборки и проверку UI resources; оформить результаты и оставшиеся ручные проверки.

Два экземпляра подтверждают HTTP/Security/SSE/outbox/worker взаимодействие. History использует обычный тестовый IChannelConnector, доступный соответствующему экземпляру. Автоматическая миграция живой TDLib-сессии, новая маршрутизация коннекторов и инфраструктура балансировки этим этапом не вводятся.

## Дополнение: звук

- У каждого сотрудника собственная настройка `soundEnabled`, сохраняемая в BE и общая для его Workspace/сессий. Для новых и существующих сотрудников по умолчанию true; старый SQL null трактуется как true без обязательного backfill.
- Self-service `GET/PUT /api/notifications/preferences`, ответ `{soundEnabled:boolean}`, `Cache-Control: no-store`. UI не передаёт employeeId. PUT требует явно заданный boolean; эффективное изменение настройки и персональная `notifications.invalidated` фиксируются одной транзакцией.
- Колонка `employee.notificationsoundenabled` создаётся Hibernate update. Profile-only UPDATE не должен восстанавливать старое значение настройки. Миграций и новых notification event types нет.
- Переключатель и проверка звука размещаются в центре уведомлений. Ошибка загрузки/сохранения видна; до получения настройки автоматический звук не воспроизводится. Предыдущая ошибка чтения не означает разрешение звука.
- Автоматический сигнал относится только к свежему, непрочитанному, неистёкшему уведомлению после stream boundary и актуальной REST ACL-проверки. Старые записи при reload/reconnect/read/resync не озвучиваются. Видимый активный диалог подавляет N2 так же, как существующий баннер.
- Фоновая вкладка может воспроизводить звук, если браузер разрешил аудио и AudioContext уже running. Нет очереди пропущенных сигналов. AudioContext создаётся/resume вызывается при взаимодействии пользователя; отказ autoplay не отключает сохранённую настройку.
- В одном origin используется отдельная дедупликация звука, по возможности атомарная Web Lock. Без Locks/доступного storage действует best effort. Разные устройства, профили браузера и HTTP-порты имеют независимую аудиодедупликацию. Во всплеске частота сигнала ограничивается одним за две секунды.
- Фоновое чтение деталей ограничено двумя активными кандидатами с дедлайном пять секунд, без очереди. Если уже разрешённое аудио готово, но `notifications.invalidated` сбросил настройку, новое событие запускает одну общую для кандидатов проверку preferences с дедлайном пять секунд вне приостановленного общего GET budget. Детали читаются только после подтверждения `soundEnabled=true`; выключенная настройка/неготовое аудио не запускают чтение. Ошибка учитывает backoff/Retry-After, поздний foreground-ответ не сбрасывает новый backoff. Смена доступа/аккаунта/области/logout отменяет запросы и отложенные callbacks. Обычный read/read-all отменяет прежние transient alerts, сохраняя установленную stream boundary для будущих событий.

Ограничения браузера: [Chrome autoplay](https://developer.chrome.com/blog/autoplay/), [Web Locks](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API). Звук выключенного/замороженного браузера и физическое воспроизведение ОС автоматически не гарантируются.

## Матрица доказательств

Матрица объединяет основной и повторный прогоны 11.12 от 26.10.2026. Последующая доработка информативности центра и исправления финального ревью описаны ниже. Новые сквозные сценарии дополняют существующие проверки, а не повторяют каждую гонку браузером. Общие BE/UI suites и browser harnesses перечислены с логами.

| Критерий | Основные существующие проверки | Дополнение 11.12 / результат |
|---|---|---|
| HTTP/Kafka fanout, две сессии, роли, отдельные gateway groups | NotificationBrowserAcceptance, UiTransportIntegrationTest, AccessPolicyIntegrationTest | PASS: два полных HTTP приложения, отдельные группы, desktop/mobile |
| Организация/Workspace/assignment/private objects, актуальная ACL при stale invalidation/DB failure | AccessPolicyIntegrationTest, AccessPolicyFailureTest, InboxBrowserAcceptance, NotificationSourceIntegrationTest | PASS: DB/policy регрессия и межсерверная web-проверка разрешённой/запрещённой области |
| Rollback, повтор/перестановка, crash/повтор публикации до и после Kafka ack | UiOutboxIntegrationTest, UiOutboxRelayTest, NotificationSourceIntegrationTest | PASS: детерминированная интеграционная регрессия |
| Kafka outage при живом REST, reconnect/rebalance, restart gateway | UiTransportIntegrationTest, NotificationStageBrowserAcceptance | PASS: настоящий broker pause/restore и перезапуск полного BE context, desktop/mobile |
| REST-сверка при живом heartbeat и пропуске relay | NotificationBrowserAcceptance: rare REST control | PASS: desktop/mobile с production control interval 120–144 секунды, без нового messages.invalidated, с сохранением черновика |
| Dirty во время REST/ошибки, A→B→A, hidden/offline, continuous invalidation | UI recovery/resource tests, InboxBrowserAcceptance, NotificationBrowserAcceptance | PASS: UI/Inbox и нагрузочная сходимость; результаты профилей ниже |
| Logout/JWT expiry/access revoke очищают UI/центр/старые callbacks | UiTransportIntegrationTest, InboxBrowserAcceptance, NotificationCenterBrowserAcceptance | PASS: BE/UI/browser и sound lifetime regressions |
| Центр после reload/restart, read/read-all, новый arrival во время команды, недоступная цель | NotificationCenterIntegrationTest, NotificationCenterBrowserAcceptance | PASS: DB/HTTP, центр desktop/mobile, общий read-state на двух BE |
| read-all Idempotency-Key/snapshot, changed payload, удалённый target/membership | NotificationCenterIntegrationTest, AccessPolicyIntegrationTest | PASS: DB/HTTP регрессия |
| History lost response/discovery/replay terminal key, restart/lease/fencing/quarantine | HistorySyncIntegrationTest, HistoryOperationWorkerTest, HistorySyncBrowserAcceptance | PASS: DB/worker, 10 браузерных сценариев и доступ через другой полный экземпляр; тестовый IChannelConnector |
| LIVE/HISTORY_IMPORT в обоих порядках, аудитория включая пустую, retention dedup, sendAttemptId | NotificationSourceIntegrationTest, OutboundAttemptIntegrationTest | PASS: финальная регрессия; импорт не создаёт storm |
| Mobile ownership/multi-device/rebind/revoke/endpoint rotation, одинаковые notificationId/readAt | PushFoundationIntegrationTest, PushDeliveryStoreTest, MobileNotificationHttpIntegrationTest | PASS: REST + штатный scheduled worker + тестовый provider |
| Push retry/timeout/rate limit/TTL, поздняя ошибка/claim, ACL/read-state до отправки | PushDeliveryWorkerTest, PushFoundationIntegrationTest, MobileNotificationHttpIntegrationTest | PASS: сквозные HTTP/provider сценарии и детерминированная регрессия |
| Два gateway не умножают логические intents; retry может увеличить число provider attempts | PushFoundationIntegrationTest, NotificationSourceIntegrationTest, NotificationStageBrowserAcceptance | PASS: общая БД/две gateway группы, проверка unique tuple |
| Slow client/overflow/import не блокируют consumer; bounded REST/queues/workers | UiSseGatewayTest, UiTransportIntegrationTest, UI requestBudget tests | PASS: BE/UI и локальный профиль; фактические пределы запросов и очередей приведены ниже |
| Read/resync/старый created не создают новый alert и не продлевают TTL | NotificationCenterBrowserAcceptance, UI notification tests, NotificationSourceIntegrationTest | PASS: центр и sound dedup/stale/read/expired/reconnect tests |
| Настройка звука default true, own-only, persistence, stale profile race | NotificationPreferencesIntegrationTest | PASS: 10 DB/HTTP сценариев, включая SQL null и stale profile UPDATE |
| Sound toggle/error/autoplay, foreground/background, duplicate, logout | UI sound tests + NotificationStageBrowserAcceptance | PASS: вызов реального AudioContext и источника звука; физическое прослушивание — ручная проверка |
| Inbox: сообщения/retry/history/groups/filters/scroll/draft/avatar | InboxBrowserAcceptance, HistorySyncBrowserAcceptance, NewConversationAvatarBrowserAcceptance, UI tests | PASS: релевантная регрессия этапа 10, desktop/mobile |
| Health/metrics/privacy/shutdown/flag rollback/resources | ManagementAvailabilityIntegrationTest, UiTransportMetricsTest, NotificationDiagnosticsTest, NOTIFICATION_OPERATIONS.md | PASS: BE/UI, production/rollback builds и SHA-256 сверка всех 46 UI resources |

## Локальный профиль и критерии до запуска

Профиль является локальной приёмкой, а не production SLO. Перед выполнением записать CPU/память/ОС/JVM/browser, реальные флаги и размеры pool. Проверки Maven/Docker выполняются последовательно: контейнеры имеют фиксированные имена и PostgreSQL port 15432.

- Два экземпляра BE, восемь активных browser contexts; два сотрудника, supervisor и SUPER_ADMIN, по две сессии. Разные browser contexts сохраняют независимую авторизацию. Системные возможности SUPER_ADMIN не означают право чтения чужого персонального центра.
- Двадцать диалогов, тысяча сохранённых уведомлений, двадцать тысяч опубликованных outbox rows; активные строки учитываются отдельно от архива.
- Обычный режим: пять минут по два подтверждённых изменения в секунду. Контрольные изменения имеют идентификаторы и проверяемый результат в DOM. Для критерия используется request→DOM как консервативная верхняя оценка commit→DOM: она включает отправку HTTP и транзакцию. Post-commit marker→DOM записывается отдельно, это не точное время commit. Порог p95 обычного режима — 5 секунд. Deadline/потеря контрольного изменения — ошибка, а не исключение из выборки.
- Всплеск: сто изменений за две секунды. Coalescing допускает пропуск промежуточных снимков, но конечное состояние каждого затронутого открытого ресурса должно совпасть с REST/БД. Записать число фактических GET на контекст/ресурс и максимум параллельных запросов.
- Настоящий Kafka outage — 30 секунд при работающем REST; восстановление измеряется отдельно от normal p95. Контрольная REST-сверка при живом heartbeat имеет собственное окно 120–144 секунды плюс REST; сценарий relay gap проверяется отдельно. Автоматический критерий восстановления — конечное состояние за 180 секунд после восстановления доступных зависимостей без продолжающихся внешних ошибок.
- Допустимы duplicate invalidations/provider attempts согласно at-least-once; недопустимы дополнительные notification records/logical delivery intents, утечки ACL, потеря принятой операции или откат terminal state.
- Backlog должен вернуться к исходному уровню после прекращения источника нагрузки; записать drain time/oldest age. Очереди, pool и активные задачи не превышают настроенные пределы. Память/потоки/соединения записываются в начале, на пике и после восстановления; короткий локальный прогон не доказывает отсутствие долговременной утечки.

## Результаты

### Исправления, найденные при сквозной приёмке

Два полных экземпляра работают в одном JVM: отдельные HTTP/Security/JPA/SSE/Kafka contexts, общие PostgreSQL/Kafka. Restart закрывает и заново поднимает полный Spring context на прежнем порту. Это не проверка падения отдельного OS-процесса; JVM heap/threads относятся к общему процессу.

- При переназначении диалога старый владелец мог сохранять открытую переписку до контрольной REST-сверки. `UiAssignmentEvents` сохраняет прежнюю аудиторию перед изменением и посылает утратившим доступ минимальный `access.invalidated`. Текущая аудитория пересчитывается policy после flush. Unassigned → owner учитывает всех прежних обычных читателей.
- При отказе Kafka ошибка POST read через servlet ERROR dispatch превращалась в посторонний 401 и разлогинивала браузер. Security допускает только контейнерный ERROR dispatch к настроенному error route, сохраняя исходный 5xx. Новый REQUEST `/error` и бизнес-API защищены. ASYNC/ERROR продолжение SSE требует атрибут уже допущенного запроса; одного URI недостаточно. Регрессия — `SseErrorContinuationIntegrationTest`.
- GET preferences до invalidation не может вернуть старое разрешение звука: синхронный revision fence закрывает окно до повторной загрузки. Ошибка необязательной настройки не блокирует визуальную ленту; собственный backoff учитывает `Retry-After` и переживает смену Workspace.
- Основной звук — добавленный пользователем `/notification.ogg`, буфер загружается при активации аудио. При недоступном/неподдерживаемом файле используется короткий тихий тон. Лимиты: загрузка пять секунд, допустимый файл 256 KiB, воспроизведение пять секунд; новый источник останавливает предыдущий. Web preference не отключает центр или native push.

### Перепроверка источников 11.9

Обнаружены отсутствовавшие writers. Добавлены явные hooks в существующие сервисы и один `UiDomainEvents`; AOP/entity listeners, новые scopes и connector routing не вводились. Изменение и outbox фиксируются одной транзакцией, доставка проверяет текущую ACL. Проверенные scope hints inbox/clients/channels/groups/filters сохраняют только свой тип, без resource/Workspace/correlation данных. Это обновляет соответствующие mounted ресурсы и не сбрасывает настройку звука при обычном изменении профиля контакта. Неизвестная версия/тип и изменения общей identity требуют полной REST-сверки. Перезагрузка страницы не используется.

| Источник | Покрытие и аудитория |
|---|---|
| Message HTTP/Kafka/callback | Исходящий/forward, processing/delivery/read/failure, retry и batch read; входящие сохраняют прежний LIVE/HISTORY_IMPORT. CONVERSATION с текущей policy; no-op watermark не пишет outbox |
| Assignment/self-assignment | Workspace + FORMER_SCOPE для утративших доступ; rollback удаляет оба сигнала |
| Личные группы и состав | Create/rename/delete/add/remove; только владелец, стабильный self resource переживает удаление группы |
| Saved filters | PRIVATE только владелец; SYSTEM текущая Workspace-аудитория; delete не зависит от исчезнувшего filter target |
| Client и связи | CRUD, создание из диалога, avatar, link/reassign/unlink; directory организации и затронутые Workspace, включая прежнего клиента |
| Глобальный ClientAccount | CRUD/эффективный inbound profile и сохранённый outbound avatar; все использующие Workspace, каждая со своей организацией. Повтор одинакового inbound profile не создаёт события |
| Channel | CRUD/account profile/disconnect; общий ChannelStatusService для beginConnecting/transition/expected disconnect, только эффективная смена состояния |
| Workspace/Organization | Create/update/delete/avatar и профиль организации; текущая организация. Удаление допустимого пустого Workspace отправляет cleanup прежним читателям; FK ограничения populated Workspace сохранены |
| Employee/membership | Профиль/credentials/enabled/delete/avatar; assign/remove/supervisor. Self identity + разрешённый metadata scope; disabled/deleted employee отсекается текущей policy и завершается stream |

Постоянные DB-проверки — `UiDomainEventsIntegrationTest` (11) и `UiAssignmentEventsIntegrationTest` (5). Помимо проверки строк, настоящий relay публикует PERSONAL/WORKSPACE/ORGANIZATION/FORMER_SCOPE в Kafka: обязательный служебный resource остаётся в outbox, в SSE scope hints он не копируется. Дополнительный desktop/mobile сценарий изменяет приватные группы/фильтры и имя контакта через HTTP первого BE и проверяет DOM второго, запреты другому сотруднику, удаление и сохранение черновика.

Страницы Clients/Channels/Employees/Workspaces и детали Client/Workspace подключены к существующему `useUiResource`. Запросы получают AbortSignal и общий budget, старый scope отменяется; смена сессии/аккаунта/роли/Workspace и цели details размонтирует старую страницу. Фоновая сверка не возвращает начальный spinner и не сбрасывает черновик клиента или снимок редактируемого Workspace. Employees и Workspaces используют по два GET независимо от размера справочника, детали Workspace — пять; HTTP fanout по сотрудникам/пространствам убран. Нового scheduler/кэша/фреймворка нет.

Для этого добавлены обычные directory read endpoints с `Cache-Control: no-store`: `GET /api/employees/organization/{id}/memberships` и `GET /api/workspaces/statistics` доступны только действующему SUPER_ADMIN своей организации; `GET /api/workspaces/{id}/memberships` — SUPER_ADMIN своей организации или текущему супервизору указанного Workspace. Последний ответ содержит только memberships запрошенного Workspace. SQL повторно проверяет enabled/роль/организацию и supervisor flag при чтении; новые схемы/таблицы/scopes не вводятся. Статистика считает employees/channels/conversations отдельными агрегатами, включает пустые Workspace, не перемножает строки при JOIN и сохраняет прежний подсчёт сотрудников без SUPER_ADMIN.

Открытая read-only карточка канала выбирается по ID из актуального списка, обновляется вместе с ним и закрывается при удалении цели. Снимки форм редактирования сохраняют локальный ввод.

React-регрессии проверяют metadata refresh, задержанный ответ прежнего аккаунта, 320 сотрудников под production GET budget, постоянные два GET для 320 Workspace, обновление/удаление открытой карточки канала и сохранение формы. Сквозной desktop/mobile сценарий открывает все шесть страниц на втором BE, изменяет данные через первый и проверяет обновления и оба черновика. Две прежние проверки частоты/backoff переведены на управляемые часы: краткая задержка CPU не считается нарушением интервала или разрешением retry. TDLib context test ожидает освобождения native Windows файлов только в собственном JUnit temp directory; production lifecycle не менялся.

### Основные автоматические прогоны до повторного ревью 26.10.2026

| Проверка | Результат | Артефакт |
|---|---|---|
| Полный BE suite | 950/950 PASS, без skips | `server/target/notification-11-12-final-all-tests.log` |
| Полный Node/React UI suite, штатный `npm test` | 384/384 PASS, без skips | `ui/node_modules/.cache/notification-11-12-ui-final.log` |
| Inbox | 20/20 desktop/mobile PASS | `server/target/inbox-browser.log` |
| Сохранение аватара нового контакта | 2/2 desktop/mobile PASS | `server/target/new-conversation-avatar-browser.log` |
| Два полных BE: fanout, scope/privacy, справочники/черновики, outage, restart, звук | 12/12 desktop/mobile PASS | `server/target/notification-stage-browser.log` |
| History Sync | 10/10 desktop/mobile PASS | `server/target/history-browser.log` |
| Центр уведомлений | 2/2 desktop/mobile PASS | `server/target/notification-center-browser.log` |
| Общий запуск пяти browser harnesses | 46/46 browser cases, 5/5 JUnit harnesses PASS | `server/target/notification-11-12-final-browser-maven.log` |
| Восстановление SSE: две сессии, смена аккаунта/logout, bounded fallback, редкая REST-сверка | 8/8 desktop/mobile cases, 1/1 JUnit harness PASS | `server/target/notification-11-12-recovery-browser-maven.log`, `notification-browser.log` |
| TypeScript app/node/E2E | PASS | Проверены перед сборкой и финальными browser harnesses |
| Production build | PASS | `ui/node_modules/.cache/notification-11-12-build.log` |
| Rollback build, `VITE_UI_RECOVERY_ENABLED=false` и `VITE_HISTORY_OPERATIONS_ENABLED=false` | PASS | `ui/node_modules/.cache/notification-11-12-rollback-build.log` |
| Изолированный нагрузочный профиль | PASS, 1/1 browser case и JUnit harness | `server/target/notification-11-12-load-maven.log`, `notification-load-profile-before-review-fixes.json` |
| UI resources основного прогона 11.12 | 46/46 файлов совпадали по SHA-256 с `ui/dist`, включая пользовательский OGG; два старых hashed JS удалены только из `server/target/classes/static/assets` | Production JS `index-DAbLctqw.js`, CSS `index-C3l3OEEf.css`; последующая сборка указана ниже |

Итого основного прогона: 54 функциональных browser cases в шести harnesses и один отдельный нагрузочный case; пропусков нет. Этот Maven-прогон завершён 26.10.2026 в 20:29:29 MSK. `git diff --check` прошёл. Пользовательский `ui/tsconfig.app.tsbuildinfo` сохранён (SHA-256 `D74DA9B5F932A8CD8BED33843B05132D608C57ECE47A748625BAE75504B0EDFA`); исходный пользовательский staged `ui/public/notification.ogg` остался единственным staged файлом. Временные credential manifests удалены harnesses. Артефакты в `target`/`node_modules/.cache` локальные и не добавляются в Git.

Для применения нужен перезапуск BE с текущими ресурсами: в основном application включены `creation-enabled`, `history-submission-enabled` и `sse-enabled`; push отдельно не включён. Hibernate update создаёт `employee.notificationsoundenabled`, существующий null означает true. В центре уведомлений доступны личный переключатель и «Проверить звук»; разрешение браузера воспроизводить аудио появляется после взаимодействия пользователя.

### Дополнение 26.10.2026: информативность уведомлений

В списке центра и live-баннере показаны контакт и источник: например, «Новое входящее сообщение / От: Анна Петрова / Telegram · Поддержка / дата». При отсутствии displayName используется @username, затем телефон, затем текст «Контакт без имени». Для assignment/outbound failure контакт подписан «Диалог». Длинные подписи переносятся, строки не содержат исполняемого HTML.

`senderName`/`channelType`/`channelName` добавляются только в REST `templateArgs` одним SQL с текущей ACL, без N+1 HTTP/SQL запросов. Обогащаются и старые уведомления; persisted notification args, SSE/outbox и push payload не получают имён. Изменений БД и миграций нет. Имя ограничено прежним контрактом 128 Unicode-символов, неизвестный мессенджер не ломает UI.

После этой доработки прошли:

- **72 BE проверки + один JUnit harness с двумя desktop/mobile browser cases** (73 JUnit invocations): `NotificationCenterIntegrationTest` 27, `NotificationContractTest` 11, `MobileNotificationHttpIntegrationTest` 7, `PushDeliveryStoreTest` 2, `PushFoundationIntegrationTest` 25 и `NotificationCenterBrowserAcceptance` 1. Лог — `server/target/notification-context-tests.log`, BUILD SUCCESS в 20:58:41 MSK. Новые сценарии проверяют старую запись без изменения persistence, REST list/detail/read, fallback/Unicode и запрет чтения контакта после отзыва доступа.
- **386/386 UI**, без skips: `ui/node_modules/.cache/notification-context-ui-tests.log`. React-проверка подтверждает общий контекст списка/баннера без дополнительных detail GET для строк списка.
- TypeScript app/E2E, production и rollback builds: `notification-context-build.log`, `notification-context-rollback-build.log` в `ui/node_modules/.cache`.
- Все **46 UI resources** совпадали по SHA-256: `server/target/notification-context-resources.json`. Assets этого дополнения — JS `index-D9DYiGxC.js`, CSS `index-BqwNVMYa.css`. Пользовательские OGG/buildinfo сохранены. Desktop/mobile screenshots центра просмотрены; подписи не переполняют строки.

BE suite и первый нагрузочный профиль ниже относятся к основному прогону до этой доработки; последующая полная регрессия фиксируется отдельным протоколом повторного ревью. Для применения нужен перезапуск BE и обновление страницы.

### Первый локальный профиль до повторного ревью

Прогон завершён 26.10.2026 в 20:17:38 MSK. Windows 11, Intel Core i7-12700H (14 cores/20 logical processors), 16 893 206 528 bytes доступной ОС физической памяти; JVM 21.0.12, max heap 4 223 664 128 bytes; headless Chromium 156.0.8078.4. Аппаратные сведения — `server/target/notification-load-host.json`; параметры и 24 снимка ресурсов первого успешного запуска сохранены в `notification-load-profile-before-review-fixes.json`. Два BE контекста работают в одном JVM; другие Maven/UI suites во время измерения не запускались. Аудио в нагрузочном профиле выключено; звук проверен отдельными сценариями.

| Измерение | Факт | Критерий / смысл |
|---|---|---|
| Обычный режим | 600 изменений за 300 059 мс; 1200 обязательных DOM-наблюдений | 5 минут, два изменения в секунду; ни одно контрольное наблюдение не потеряно |
| Request→DOM | p95 **973 мс**, max 1659 мс | p95 ≤5000 мс; включает HTTP и транзакцию, консервативная верхняя оценка commit→DOM |
| Post-commit marker→DOM | p95 957 мс | Отдельная диагностическая метрика; marker не является точным временем commit |
| Всплеск | 100 изменений за **1998 мс**, конечный DOM всех затронутых контекстов за **4261 мс** от начала всплеска | Цель 2 секунды, допуск генерации до 3 секунд; конечное состояние сходится |
| GET во всплеске, восемь контекстов | `[40,40,39,40,37,37,37,37]` | Каждый контекст делает меньше 100 GET; промежуточные снимки coalesced |
| Отказ Kafka / восстановление | **30 046 мс** без broker; DOM и `outboxPending=0` через **3982 мс** после restore | ≤180 секунд после restore; REST доступен, logout нет, черновики сохранены |
| GET за весь профиль после стартовой загрузки | 2927–2944 на контекст; ошибки GET отсутствуют | Фактические запросы ко всем API, включая summary/preferences и метаданные; детализация `browserMetrics.paths` в JSON |
| Параллельные browser GET | максимум **2** | Лимит общего browser budget — 4 |
| Outbox | pending 0 в начале/конце; наблюдаемый max 4, oldest max 32,994 с | Backlog сошёлся после отказа; время до DOM и нулевого backlog измерено совместно, отдельный точный drain time не заявляется |
| Kafka lag | наблюдаемый max 2 records, в конце `[0,0]` | Оба gateway догнали stream |
| JVM heap / live threads | около 420→505→349 MiB (начало / наблюдаемый max / конец); threads 175→175→171 | Общий JVM двух контекстов; короткий прогон не доказывает отсутствие длительной утечки |
| SSE / DB | 4 соединения на gateway при лимите 256; в снимках SSE queue/workers/executor queue — 0; DB active max 1 при pool max 10 на контекст | Worker limit 8, executor queue limit 256, per-client queue limit 128; периодические снимки могут пропустить короткий пик |

Профиль пройден по заранее заданным локальным критериям. Эти числа не являются production SLO или доказательством поведения отдельных OS-процессов/долгой нагрузки. Ранее неудачный прогон, обнаруживший ложный 401 при ERROR dispatch, сохранён отдельно как `notification-load-profile-before-auth-fix.json` и не используется как результат готовности.

### Исправления повторного ревью 26.10.2026

1. Read/read-all из другой сессии больше не блокирует следующие звуки в скрытой вкладке: отмена прежних alerts отделена от сброса stream boundary. Неизвестная настройка проверяется одним общим ограниченным запросом; remote mute, Retry-After, поздний foreground-ответ и logout покрыты React-регрессиями. Desktop/mobile сценарий подтверждает реальный native clip после чтения через другой BE без возвращения вкладки в foreground. Только Page Visibility в headless Chromium задаётся явно; SSE, HTTP, ACL и аудиодвижок не подменяются.
2. Убран HTTP fanout справочников: два GET для Employees/Workspaces и пять для деталей Workspace независимо от числа записей. Проверки 320 сотрудников и 320 Workspace не расширяют production deadline/budget. Шесть DB/HTTP сценариев проверяют все memberships, scoped supervisor flags, агрегаты без перемножения, пустые Workspace, изоляцию организаций, disabled/expired/demoted actor, немедленный отзыв роли и `no-store`.
3. Открытая карточка канала использует ID и актуальный DTO списка. React проверяет смену статуса и удаление; browser — обновление открытого модального окна из другого BE. Черновики редактирования сохраняются.
4. Нагрузочный сценарий сверяет не только последнее сообщение и нулевой pending: после normal/burst/restore все восемь контекстов сравнивают порядок диалогов, preview и unread с REST search, а центр — summary, ID/порядок/время и read-state с REST list. После burst одно уведомление читается через HTTP, чтобы сверка на обоих BE включала прочитанные и непрочитанные строки. Каждый снимок дополнительно требует `outboxQuarantined=0`.

### Повторная регрессия до дополнения очистки

| Проверка | Результат | Артефакт |
|---|---|---|
| Полный BE suite, включая контекст отправителя и новые directory read models | **958/958 PASS**, без skips | `server/target/notification-11-12-review-all-be.log`, BUILD SUCCESS 21:48:11 MSK |
| Полный Node/React suite | **391/391 PASS**, без skips | `ui/node_modules/.cache/notification-11-12-review-all-ui.log`; после усиления assertions справочников дополнительно **5/5** в `notification-11-12-review-directories-ui.log` |
| Inbox / avatar / History / recovery / центр, desktop/mobile | **42/42 cases**, пять JUnit harnesses PASS | `server/target/notification-11-12-review-browser-maven.log` и отдельные browser logs |
| Stage: metadata/privacy, два BE/read-state, outage, restart, native sound, справочники | **12/12 последних результатов**: 10 PASS в общем прогоне и 2/2 справочников при адресном повторе | `notification-stage-browser-before-review-selector-fix.log`; `notification-11-12-review-directory-browser-maven.log`, BUILD SUCCESS 22:17:56 MSK; `notification-stage-browser.log` |
| TypeScript app / node / E2E | PASS | App проверен с `--incremental false`, node buildinfo направлен в `node_modules/.cache`; пользовательский buildinfo сохранён |
| Production / rollback builds | PASS | `ui/node_modules/.cache/notification-11-12-review-build.log`, `notification-11-12-review-rollback-build.log` |
| UI resources | **46/46** совпадают по SHA-256 | `server/target/notification-11-12-review-resources.json`; JS `index-pJum_B-1.js`, CSS `index-BqwNVMYa.css` |
| Усиленный изолированный нагрузочный профиль | **1/1 browser case и JUnit harness PASS**, без skips | `server/target/notification-11-12-review-load-maven.log`, BUILD SUCCESS 22:29:57 MSK; `notification-load-browser.log`, `notification-load-profile.json` |

Все **54 уникальных функциональных browser cases** прошли на коде завершения 11.12 до дополнения очистки. Общий Maven browser-прогон первоначально завершился с ошибкой одного harness: новый тест находил две кнопки «Закрыть» в карточке канала, уже подтвердив обновление её данных. Селектор уточнён до однозначного label; оба directory cases повторены и прошли. Остальные пять harnesses и десять Stage cases из общего прогона прошли; автоматических retries и skips нет. Исходный неудачный лог сохранён, его итог BUILD FAILURE не выдаётся за успешный общий запуск.

Именные browser logs содержат последний запуск соответствующего harness и перезаписываются при повторе. Для старых прогонов выше сохранены отдельные Maven-логи; полный Stage-лог до исправления селектора вынесен в `notification-stage-browser-before-review-selector-fix.log`.

### Итог усиленного нагрузочного профиля

Прогон на текущем коде завершён 26.10.2026 в 22:29:57 MSK. Профиль и оборудование сохранены: Windows 11, i7-12700H/20 logical processors, JVM 21.0.12 с max heap 4 223 664 128 bytes, Chromium 156.0.8078.4. Два полных BE контекста в одном JVM, общие изолированные PostgreSQL/Kafka, восемь browser contexts; другие Maven/UI suites во время измерения не запускались. Аудио выключено только для нагрузочного профиля, его functional regressions выполнены отдельно.

| Измерение | Факт | Критерий / предел доказательства |
|---|---|---|
| Normal | **600** изменений за 300 059 мс, все **1200** обязательных DOM-наблюдений получены | Два изменения в секунду в течение пяти минут; пропуск наблюдения является ошибкой |
| Request→DOM | p95 **983 мс**, max **1423 мс** | p95 ≤5000 мс; включает HTTP/транзакцию, верхняя оценка commit→DOM |
| Post-commit marker→DOM | p95 **965 мс** | Диагностическая метрика, marker не является точным временем commit |
| Burst | **100** изменений за **1999 мс**, полная сверка UI/REST за **8351 мс** от начала | Время включает открытие центра и remote read; прежние 4261 мс измеряли только последний message DOM и напрямую не сравниваются |
| GET во burst | `[64,65,69,71,42,40,42,40]` на восемь контекстов | Каждый <100; учитываются также GET открытого центра и обновление после read |
| Kafka outage / restore | **30 049 мс** без broker, UI и pending=0 восстановились за **4232 мс** после restore | ≤180 секунд; REST доступен, черновики owner/other сохранены |
| Полнота сверки | **Normal, burst, restored × 8 контекстов**: Inbox IDs/порядок/preview/unread и центр summary/list IDs/порядок/createdAt/read-state совпали с REST | Owner видит 19 диалогов, other — 1, supervisor/admin — 20; личный центр supervisor/admin остаётся пустым. После burst присутствует подтверждённый read через другой BE |
| GET после стартовой загрузки | **2942–2965** на контекст; ошибок нет, max active **2** | Общий browser budget=4; факты по путям в `browserMetrics.paths` |
| Outbox | pending **0→max 4→0**; quarantine **0 во всех 24 снимках**; oldest max **32,994 с** | Backlog сошёлся без скрытого переноса в карантин; периодические снимки могут пропустить короткий пик |
| Kafka lag | наблюдаемый max **2**, в конце **[0,0]** | Обе gateway группы догнали stream |
| Общий JVM | heap **326→388→337 MiB**, threads **174→174→171** | Начало / наблюдаемый max / конец; короткий прогон не доказывает отсутствие долговременной утечки |
| SSE / DB | 4 соединения на gateway при лимите 256; sampled queue/workers/executor queue=0; DB active max=1 при pool max=10 | Лимиты worker=8, executor queue=256, per-client queue=128; это периодические снимки |

Усиленный профиль прошёл с заранее заданными локальными критериями. Payload/notification scopes, connector routing и production SLO этим прогоном не расширяются. Пользовательские OGG/buildinfo сохранены; staged остаётся только исходный `ui/public/notification.ogg`, временные credential manifests удалены. Для применения изменений нужен перезапуск BE и обновление страницы.

### Дополнение: «Очистить уведомления»

В центре добавлена кнопка с подтверждением удаления всех личных уведомлений во всех Workspace: прочитанных, непрочитанных и скрытых текущими правами на target. Отмена ничего не отправляет. Во время команды заблокированы повторные действия, переключение фильтра и пагинация; после результата список и счётчик перечитываются, поскольку за время запроса могли прийти новые записи. Другая сессия получает штатную персональную invalidation; удалённые ID возвращают 404.

`DELETE /api/notifications` использует обязательный UUID Idempotency-Key без тела и возвращает `NotificationClearResultDto {clearedAt,deletedCount}` с no-store. Физическое удаление notification и связанных delivery, сохранение результата в существующем command journal и outbox атомарны. Dedup/occurrence не удаляются раньше retention: старый replay не возвращает очищенные записи. Повтор уже выполненной команды с тем же ключом не затрагивает уведомления, поступившие после исходной snapshot; frontend хранит неподтверждённый ключ в отдельном account/tab-bound sessionStorage в течение семи дней. Cross-operation key reuse даёт 409. Новый schema/notification scope не вводится; миграций нет.

Проверки дополнения:

- **78/78 BE**: NotificationCenterIntegrationTest 33, NotificationContractTest 11, MobileNotificationHttpIntegrationTest 7, PushFoundationIntegrationTest 25, PushDeliveryStoreTest 2; `server/target/notification-clear-targeted-be.log`. Шесть новых DB/HTTP сценариев покрывают удаление скрытых/прочитанных/непрочитанных записей и delivery, self-only SUPER_ADMIN, dedup replay, rollback, concurrent same-key, новые arrivals после commit, conflict read-all/clear, disabled/expired, UUID/body/auth и no-store.
- **395/395 UI**, полный `npm test`: `ui/node_modules/.cache/notification-clear-all-ui.log`. Добавлены confirmation/cancel, authoritative count/list, retry после потери ответа, account switch/abort/late result, изоляция read-all/clear/account/tab и TTL; проверяется блокировка фильтра на время команды. TypeScript app/E2E прошёл.
- **2/2 desktop/mobile browser cases PASS**, 1/1 JUnit harness: центр расширен очисткой в двух сессиях, 404 удалённых ID, потерей ответа после commit, reload и сохранением нового notification при retry. `server/target/notification-clear-browser-maven.log`, BUILD SUCCESS 23:16:23 MSK, `notification-center-browser.log`. После финальной блокировки фильтра на время команды browser-проверка повторена.
- Production/rollback builds и TypeScript app/E2E PASS: `ui/node_modules/.cache/notification-clear-build.log`, `notification-clear-rollback-build.log`. Текущие assets: JS `index-BUMds9Jb.js`, CSS `index-DHCKBgkX.css`; SHA-256 сверка ресурсов — `server/target/notification-clear-resources.json`.

Полный BE suite и нагрузочные числа выше относятся к завершению основной 11.12 до этой кнопки; для дополнения выполняется указанная связанная регрессия. Пользовательские БД, OGG и buildinfo сохраняются; staging/коммитов агент не создаёт. Для применения требуется перезапуск BE и обновление страницы.

## Отдельная ручная/native приёмка

Проверить слышимость/громкость, разрешение браузеру воспроизводить звук, фон/заморозку вкладки и ОС mute на целевых браузерах. Headless-проверка AudioContext/источника звука не заменяет прослушивание.

Реальные FCM/APNs adapters и Android/iOS lifecycle не входят в BE foundation. Перед native-релизом обязательны foreground/background/terminated/force-stop в пределах ОС, разрешение/отказ, offline/resume/cold-start по нажатию, переустановка и дубликаты SSE+push. До этой проверки физическую доставку или пробуждение устройства считать подтверждёнными нельзя.
