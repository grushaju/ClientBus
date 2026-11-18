# Этап 13.1 — базлайн коннекторов, контракт integration/dikidi и матрица доказательств

Документ — артефакт подэтапа 13.1 (13.11.2026). Границы этапа и последовательность — [Stage 13.txt](Stage%2013.txt); полная инвентаризация Telegram-базлайна — [CRM_CONNECTOR_INVENTORY.md](CRM_CONNECTOR_INVENTORY.md); живые проверки чат-протокола — [CRM_DIKIDI_PROTOCOL.md](CRM_DIKIDI_PROTOCOL.md), раздел «Повторная проверка чатов 13.11.2026»; живая CRM-приёмка 12.7 — [CRM_ACCEPTANCE.md](CRM_ACCEPTANCE.md). Реализация этапа не начиналась: код production-модулей не изменён.

## 1. Выводы базлайна (Telegram)

Инвентаризация подтверждает план: SPI уже существует (`IChannelConnector`, команды-records, `ChannelConnectorRegistry`, `ChannelAccountLifecycleRegistry`), внешние callers реестра — ровно 6 классов ядра плюс lifecycle-пара в `ChannelService`. Главная работа 13.2/13.3 — не «создать SPI», а вырезать обратные зависимости: `SendMessageCommand → server.service.ChannelAttachment`, `TelegramContextFactory` (дочерние Spring-контексты с семью серверными бинами), listeners с `MessageService.markReadUpTo(long)`/`recordExternalOutcome`, контроллер авторизации с `CurrentUserService` и репозиториями.

Дополнительные требования, найденные базлайном (фиксируются как задачи переноса):

1. `TelegramClientManager.closeAll()` не вызывается из main — при 13.3 нужен явный owner жизненного цикла (shutdown hook сборки), иначе TDLib-контексты переживают graceful stop.
2. Аватары Telegram, сохранённые в attachment storage, не удаляются при disconnect/удалении канала — в контракт вложений (п.7 Stage 13) входит очистка «сохранённых платформенных ресурсов» с владельцем.
3. Мёртвые места SPI не переносить без решения: `handle(SyncClientAccountCommand)`, `IChannelEventHandler/ChannelEventHandler`, `IPlatformEventPublisher`, `TelegramChannelAccountService.restart` — в 13.2a либо удалить, либо обосновать.
4. Фактические маршруты авторизации — `/api/channels/{channelAccountId}/telegram/*` (9 ручек), отдельных `/telegram/*` нет; web-адаптер 13.3 сохраняет именно их.
5. `spring.telegram.client.*` (api-id/api-hash/encryption-key) — константы в `application.yml`, env-переменных нет. С 15.11 сессии TDLib лежат в абсолютном `database-directory` (по умолчанию `D:/Projects/Java/ClientBus/connector/telegram/data`, переопределение `TELEGRAM_DATA_DIRECTORY`; прежний `./data/telegram` зависел от CWD процесса), layout `<uuid>/{database,files}` не менялся. Штатный останов более не читается как разлогин: Closing/Closed штампуют `CONNECTOR_SHUTDOWN`, а аккаунты `DISCONNECTED` с сохранённой на диске сессией автоматически поднимаются на старте (`TelegramClientStartupService` + `TelegramDataStorage.hasPersistedSession`) — «Отключить» удаляет сессию и не воскрешается, `DISABLED` вне выборки. Ключи (api-hash/encryption-key) по-прежнему требуют выноса в настройки до релиза коннектора.
6. `clientbus.kafka.outbound.enabled-channels` перечисляет WHATSAPP/VK без коннекторов; `springdoc` сканирует telegram-пакет в общих ресурсах — убирается по 13.3 (server без Telegram собирается и описывается без него).
7. UI-жёсткость: `NewConversationDialog` фильтрует TELEGRAM, `ChannelsPage` дёргает telegram-маршруты для любого канала — объём 13.7.

## 2. Контракт integration/dikidi (v1.0, закреплён реализацией в 13.4)

Модуль не зависит ни от чего в репозитории (ни server, ни common, ни crm/api, ни connector/api) — технический ящик DIKIDI. Публичные типы (итоговые имена):

| Тип | Назначение (по факту реализации) |
|---|---|
| `DikidiCredential` | connectionId, credentialVersion, login, char[] секрет; AutoCloseable, обнуляется по закрытию (зеркало CrmAccess). cookie/CSRF наружу не отдаются |
| `RequestProfile` | CRM / OWNER_CHAT: собственные allow-list хостов + корректный Referer (CRM — журнал/проекты, OWNER_CHAT — страница владелец-чата) |
| `DikidiTransport` | транспорт: `exchange(method,uri,form,ajax,projectId,timeout)` + перегрузки с Observer (beforeSend/dispatched/response) и RequestProfile; `Response(status,body,retryAfter)` |
| `DikidiFailure` | AUTH_REQUIRED, ACCESS_DENIED, RATE_LIMITED(+retryAt), COOLDOWN, CONNECTION_BUSY, TEMPORARY_FAILURE, INVALID_RESPONSE; флаг notDispatched |
| `DikidiClient` | `execute(credential, until, work)` — single-attempt recovery для доказанных чтений; `executeOnce` — никогда не повторяет (изменяющие операции); один экземпляр на сборку, `close()` — только владелец-сборка |
| (внутри) пул сессий | ключ `connectionId + credentialVersion`; одиночный вход на ключ (tryLock 2s → CONNECTION_BUSY); ёмкость 128, eviction 20 мин; `invalidate(connectionId)` гасит все версии |
| `DikidiRequests` | перенесённый технический regulator (token bucket локальный+глобальный, Retry-After/экспоненциальный fallback) поверх `DikidiTrafficPort` |
| `DikidiSessionGate` | порт guard'а единственного активного runtime (реализация — lease/heartbeat-строка сборки, §7) |

`DikidiCallClass` (INTERACTIVE/SYNC/BULK) в v1.0 не введён: действуют общий caller deadline (ограничение ожидания сессии/очереди/входа/redirects, не возобновляется после reauth) и профили запросов; классы бюджета — вместе с чатовым трафиком 13.5. Обновление 13.5: чатовое чтение введено и приоритет держится конструкцией — чтение берёт session-lock на каждый hop с ожиданием 2 с и уступает интерактиву (CONNECTION_BUSY), бакеты per-connection и global общие, операция чтения = shell + ограниченные страницы; явные классы бюджета вводятся в 13.6 вместе с отправкой (первым реальным конкурентом интерактива).

Политики, обязательные с первой реализации:

- **Повторы.** `execute` текущего `DikidiProvider` умеет повторять чтение после AUTH_REQUIRED; в общий клиент это переносится только для доказанно безопасных чтений (не отправлено ни одного байта эффекта / ответ не начался / операция размечена read-only). Изменяющие операции не повторяются никогда: timeout после возможного эффекта → UNKNOWN с внешней сверкой, не resend.
- **Разрешение адресов.** Разрешённый набор `(host, method, path-prefix)` передаётся профилем адаптера и проверяется перед каждой фактической отправкой, включая каждый hop redirect. CRM-адаптер: dikidi.ru/auth.dikidi.ru; чатовый профиль добавляет chat.dikidi.ru. Расширение чатового хоста не даёт CRM-адаптеру новых endpoint.
- **Cookie/CSRF.** Jar живёт в сессии пула; domain/path по RFC; `X-XSRF-TOKEN` подставляется по cookie соответствующего домена (13.1 показал: токен нужно брать по домену запроса, а не только dikidi.ru). Значения наружу не раскрываются.
- **Bootstrap-последовательности принадлежат адаптерам.** CRM: вход + инициализация проекта (sw.auth.complete и journal-контекст). Чаты: вход → **GET страницы `https://dikidi.ru/ru/owner/chat/?company={P}`** → chat API (доказано 13.11.2026: без визита страницы все chat API дают HTTP 400 «Недостаточно прав» при валидной сессии).
- **Бюджеты и приоритеты** (из ревью, закреплены в 13.1): один технический клиент обслуживает CRM-обращения и чаты; классы INTERACTIVE > SYNC > BULK; текущий `Semaphore(4)` на подключение и провайдерский traffic governor переносятся как есть; starvation-сценарии проверяются в приёмке 13.5.
- **Consumer-бюджет.** Синхронный коннекторный вызов из Kafka outbound-consumer обязан укладываться в согласованный бюджет, заметно меньший `max.poll.interval.ms` (пер-вызов либо executor с сохранением claim/lease); превышение — UNKNOWN, не потеря и не авторетрай.
- **Deadline.** Caller-бюджет ограничивает ожидание сессии, очередь, вход, bootstrap и redirects; не возобновляется после reauth.
- **Здоровье.** Подтверждённая потеря сессии инвалидирует ключ у обоих адаптеров согласованно и коалесцируется (один reauth одновременно); старые handles после смены credentialVersion не работают. Инвалидация во время многостраничного CRM-чтения оставляет порцию retryable/UNKNOWN-safe (сценарий матрицы Stage 13).
- **Настройки.** Новые ключи `clientbus.dikidi.*`; существующие `clientbus.crm.connect-timeout-ms/request-timeout-ms/max-response-bytes` остаются fallback. Ключи шифрования живут на server.
- **Единственный runtime.** Guard: второй экземпляр процесса с тем же connectionId детектируется (lease/heartbeat) и приводит к явной деградации + алерту (требование с 13.4).

## 3. Capability-матрица чатов DIKIDI (по состоянию на 13.11.2026)

| Возможность | Статус | Доказательство / ограничение |
|---|---|---|
| C01 оболочка чата | ПРОВЕРЕНО | 200, JSON-форма; `/ru/owner/ajax/chat/page/?company=` |
| Bootstrap-предусловие | ПРОВЕРЕНО (новое) | chat API без предварительного GET `/ru/owner/chat/` → 400; контроль без shell-визита воспроизвёл 400 на всех referer |
| C02 список диалогов | ЧАСТИЧНО | 3 диалога, `more=false`; многостраничный `time`-курсор не проверен (данные малы) |
| C03 непрочитанное | ПРОВЕРЕНО ЧАСТИЧНО | форма map `dialogId→count`; `total` ≠ сумма сообщений — не счётчик |
| C04 детали диалога | ДРЕЙФ | `data=[dialog,messages,users]`; сообщения пустые при `messages=1`, последнее сообщение в `dialog.msg`; `users[]` неоднороден (скаляры+объекты), `role`/`is_open_session` не прочитываются |
| C05 история | ЧАСТИЧНО | стабильный набор полей `[date,emoji,id,joining,out,read,seen,status,text,user_icon,user_id,user_name]`; `date` — epoch; терминальная пустая страница по `last`; несколько непустых страниц не подтверждены |
| Классификация support/клиент | ОТКРЫТО | у диалогов больше нет `role`/`concept` (были 27.10) — структурного маркера не наблюдалось |
| Несколько диалогов на клиента | НЕИЗВЕСТНО | 3 диалога — 3 разных входящих `user_id`; контрпример не найден, ограничение не выводится |
| Область chat user ID | НЕИЗВЕСТНО | один проект; кросс-проектных доказательств нет → namespace подключения остаётся планом по умолчанию |
| Таймзона epoch | НЕИЗВЕСТНО | UTC vs локальное время не различены |
| Отправка (текст+файлы) | ПОДТВЕРЖДАЕТСЯ ЖИВО | C06 `POST chat.dikidi.ru/api/dialog/message/`: response-захват 15.11.2026, форма (`tabHash/dialog/text/files[]`) — публичный код клиента; `error.code=0` + сохранённое сообщение. Файлы — C07-upload + один C06 (живой захват 302530210). Форма files[] живьём не проверялась — при неверной форме `error.code≠0`, фантомной отправки нет |
| Отметка прочтения | ПОДТВЕРЖДАЕТСЯ КОДОМ КЛИЕНТА | C08 `POST chat.dikidi.ru/api/dialog/read/` `{tabHash, dialog, msg[]}` — форма из публичного backend.js (захвата запроса не было); ответ живьём не проверялся; операция идемпотентна |
| Отчёт о доставке | РЕАЛИЗОВАН ОСТОРОЖНО | Единственный доказанный receipt: исходящее `read>0` → READ (через ingest-эхо, `recipientRead`; продвижение только SENT/DELIVERED). DELIVERED в DIKIDI доказанного представления нет — не синтезируется. Обновления/стриминг, создание диалога, поиск контакта — не проверялись (вне объёма) |

## 4. Прогоны-доказательства 13.1

Класс-пробник: `server/src/test/java/kit/penny/clientbus/crm/dikidi/CrmDikidiChatReadAcceptance.java` — opt-in (`clientbus.crm.acceptance.live=true`), только GET + два POST входа, жёсткий allowlist адресов, бюджет 40 вызовов, вывод без PII (структура, счётчики, имена полей, обезличенные извлечения). Проект `f024d60c-4d37-4f6c-8431-ff92c0411248`.

Класс-зонд 13.9: `CrmDikidiClientIdentityAcceptance` там же — ищет **провайдерский ключ** «карточка клиента ↔ её чат» (R09/R08/R07/R05 против C02/C04), opt-in, бюджет 60 вызовов (фактически 27 и 15 на двух проектах), кандидатов id берёт из локальной БД только для сравнения в памяти, вывод — имена полей, форма значений, заполненность и булевы совпадения. Логи: `server/target/crm-13-9-identity-probe-0ff3a37c.log`, `server/target/crm-13-9-identity-probe-f024d60c.log`. Итог: ключа нет, телефон остаётся единственной подсказкой — `CRM_DIKIDI_PROTOCOL.md`, раздел «Поиск ключа „карточка клиента ↔ чат“».

| Лог | Содержание |
|---|---|
| `server/target/crm-13-1-chat-probe-run2.log` | зелёный прогон после discovery (16 вызовов, writes=0) |
| `server/target/crm-13-1-chat-probe.log` | финальный прогон с именами полей (16 вызовов, writes=0) |
| `server/target/crm-13-1-chat-probe-noshell.log` | контроль: без shell-визита C02 = 400 на всех вариантах referer |
| `server/target/crm-13-1-chat-probe.log` (первый запуск) | исходное 400, приведшее к открытию предусловия |

## 5. Минимальный клиентский сценарий против дополнений

База этапа (после 13.4–13.5): bootstrap (вход+shell), список диалогов, непрочитанное как invalidation-сигнал, история C05 с `last`-курсором и epoch-датами, направление по `out`, автор внешних исходящих без Employee. Обновление 13.6: отправка текста/файлов, отметка прочтения и receipt прочтения введены по подтверждениям таблицы выше; всё остальное — обновления/стриминг, поиск контакта, создание диалога — включается только после отдельных подтверждений и не входит в минимальный контракт.

## 6. Открытые вопросы к 13.2

1. Форма `users[]` C04 и источник канонических участников: скалярный ID vs объект; нужен разбор реального payload-образца перед закреплением портов идентичности.
2. Классификация служебных диалогов (поддержка DIKIDI): структурный маркер утрачен — искать среди полей C02/C04/отдельного endpoint либо принимать явным allowlist-правилом пользователя.
3. Множественность диалогов на клиента и область chat user ID — требуют второго тестового проекта или многостраничных данных.
4. Семантика `time`-курсора C02 и таймзон epoch — уточнить при появлении больших выборок.
5. Изменяющие операции (send/read-mark): семантика ответа, echo-подавление, UNKNOWN-сверка — только 13.6 на согласованных данных.

## 7. Жизненный цикл канала DIKIDI и единственный активный runtime (13.4)

Общая сессия: CRM-адаптер (crm/dikidi) и чатовый канал (connector/dikidi) пользуются одним
`DikidiClient` (bean сборки). Вход — один на версию учётных данных; одновременные повторные
входы CRM и чата исключены блокировкой сессии. Секрет не копируется: каждый потребитель
получает свой `DikidiCredential` из расшифровки хостом, версий ключа шифрования не меняется
(тот же AAD: organizationId+connectionId+keyId).

- **close-владелец.** Транспорт сессий закрывает только сборка (`DikidiClient.close()` при
  остановке контекста). `DikidiProvider.close()` (локальная остановка CRM-адаптера) НЕ закрывает
  клиент, и наоборот — локальное выключение одного потребителя не влияет на другого (тест
  `adapterCloseDoesNotDisturbTheSharedClient`).
- **enable/disable подключения (CRM).** Смена секрета/логина или флага enabled поднимает
  `credentialVersion` и инвалидирует сессию ПОСЛЕ commit (rollback не рушит живые сессии;
  commit не оставляет сессий старой версии). Старые handles с старой версией ключа не работают:
  новая операция входит заново либо падает AUTH_REQUIRED по прежным правилам.
- **disconnect аккаунта канала.** Ожидаемое отключение через `HostChannelStatus.expectDisconnect`
  (13.5 для DIKIDI-аккаунтов); сессия клиента при этом не рвётся — она переиспользуется CRM.
- **Удаление аккаунта канала / CRM-подключения.** Хост инвалидирует сессию (`registry.invalidate`
  тоже после commit). Удаление подключения отвергается, пока живая lease-строка runtime
  свидетельствует, что доступ активно обслуживается (channel binding в проверке удаления), а
  также при привязанных проектах/операциях (прежние проверки).
- **Провизионизация канала (решение 15.11.2026, реализовано в 13.5).** Канал DIKIDI в
  workspace создаётся автоматически при назначении DIKIDI-CRM-проекта на workspace;
  удалить его напрямую нельзя — только вместе с DIKIDI-проектом; управление —
  подключить/отключить/изменить (эндпоинты `POST /api/channels/{accountId}/dikidi/connect|disconnect`,
  общая ручка удаления канала для DIKIDI отвергает запрос). Поля channelaccount берутся только
  из БД, без обращений к DIKIDI: externalId = crm.project.externalid (company id), name =
  crm.project.displayname, phone = crm.connection.login (DIKIDI-логин и есть телефон), username =
  crm.connection.displayname (имя подключения). Повторное назначение refreshed профиль по тем же
  правилам и реактивирует канал (DISABLED → CONNECTING); смена подключения DIKIDI-проекта
  (changeConnection) обновляет профиль канала по тем же правилам. Конфиг-привязка
  `clientbus.dikidi.chat.connection-id/company` удалена вместе с AutoConfiguration 13.4;
  сборка бинов — `DikidiChatConfiguration` на общем `DikidiClient`.
- **Отвязка/архивирование проекта (реализовано в 13.5).** Снимает CRM-привязку workspace
  (прежняя семантика 12.x) и решает судьбу канала: без переписки канал удаляется вместе с
  чекпойнтами диалогов; при наличии переписки канал отключается и остаётся `DISABLED` —
  архивная история сохраняется (FK диалогов и политика 12.x; записанное отклонение от
  «удалить вместе с проектом» для этого случая). Физического удаления проектов в модели нет.
- **Входящие и история (13.5).** Классификация диалогов без догадок: ровно два участника и
  разрешимый владелец по пробнику сообщений (автор `out=1`, иначе единственный автор входящих);
  поддержка DIKIDI и неизвестная форма пропускаются с логом. Источник событий: начальный импорт —
  History-путь (хост форсит HISTORY_IMPORT — персональных уведомлений нет); сообщения после
  завершённого initial sync публикуются коннектором как LIVE (`HostInboundPublisher`). Курсоры —
  `dbo.chatcheckpoint` на диалог (newest/oldest, initialSyncDone, lastDialogEpoch); продвигается
  после передачи страницы, повторы гасятся дедупом сообщений по внешнему id. Направление:
  `out=1` → sender = externalId канала (эхо, Employee=NULL по CRM_SEMANTICS §6). Начальная
  история не объявляется полной, пока не доказана пустой страницей C05; бюджет страниц на тик —
  `clientbus.dikidi.chat.initial-pages`, недогруженное продолжается с чекпойнт-курсора.
  Опрос — ограниченный polling общим `RecentChatsSyncScheduler` (PT15M) + периодическая доводка
  доступа `clientbus.dikidi.chat.recheck-ms`.
- **Перезапуск процесса.** На ApplicationReady канал повторно проверяет доступ к чатам
  (bootstrap C01→C02 через общий клиент); сессия DIKIDI не переживает рестарт (транспорт
  закрыт), повторный вход дешёв. Lease-строка переживает рестарт в пределах TTL 90 с; после
  неё тот же экземпляр возвращает lease себе.
- **Проверка доступа до CONNECTED.** `DikidiChatChannel.refresh()`: успех = shell 200 +
  dialogs/list 200 с `data.list` (C01/C02, профиль OWNER_CHAT). Отказ = канал не входит в
  CONNECTED (аккаунты DIKIDI → ERROR с причиной `DIKIDI_CHAT_ACCESS_...`). Успешный CRM-health
  проверкой чатов не считается.
- **Единственный активный runtime.** `crm.connectionruntime(connectionid, owner, heartbeatat)`:
  заявка upsert-ом, heartbeat каждые 30 с, TTL 90 с. Второй экземпляр с тем же connectionId
  получает явную деградацию (`TEMPORARY_FAILURE`, notDispatched, retryAt +30 с) и
  ERROR-алерт `DIKIDI_RUNTIME_LEASE_TAKEN`, а не молчаливую двойную работу. Guard дешёвый
  (две PK-операции на операцию клиента, до транспорта); полноценный claim/lease
  многоэкземплярной топологии — 13.8. До готовности хранилища guard не может ни подтвердить,
  ни опровергнуть — пропускает без деградации. Перехват протухшего lease работает только потому,
  что проигравший standby чужую живую строку не греет (heartbeat сдвигает только владеющая или
  перехватываемая по TTL строка; ревю 13.4): после нештатной гибели владельца её строка стареет
  за TTL и достётся следующему. Lease-строка удалённого подключения снимается вместе с удалением.
- **Retry-границы (не регрессировали).** Чтения: ровно один повтор после AUTH_REQUIRED через
  восстановление сессии (`execute`). Изменяющие: `executeOnce` — никакого повтора после потери
  сессии; evidence notDispatched/may-have-dispatched сохраняется через Observer-хуки; 429
  реавторизацию не вызывает. Восстановление сессии никогда не повторяет отправку.
