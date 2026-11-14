# Этап 13.1 — базлайн коннекторов, контракт integration/dikidi и матрица доказательств

Документ — артефакт подэтапа 13.1 (13.11.2026). Границы этапа и последовательность — [Stage 13.txt](Stage%2013.txt); полная инвентаризация Telegram-базлайна — [CRM_CONNECTOR_INVENTORY.md](CRM_CONNECTOR_INVENTORY.md); живые проверки чат-протокола — [CRM_DIKIDI_PROTOCOL.md](CRM_DIKIDI_PROTOCOL.md), раздел «Повторная проверка чатов 13.11.2026»; живая CRM-приёмка 12.7 — [CRM_ACCEPTANCE.md](CRM_ACCEPTANCE.md). Реализация этапа не начиналась: код production-модулей не изменён.

## 1. Выводы базлайна (Telegram)

Инвентаризация подтверждает план: SPI уже существует (`IChannelConnector`, команды-records, `ChannelConnectorRegistry`, `ChannelAccountLifecycleRegistry`), внешние callers реестра — ровно 6 классов ядра плюс lifecycle-пара в `ChannelService`. Главная работа 13.2/13.3 — не «создать SPI», а вырезать обратные зависимости: `SendMessageCommand → server.service.ChannelAttachment`, `TelegramContextFactory` (дочерние Spring-контексты с семью серверными бинами), listeners с `MessageService.markReadUpTo(long)`/`recordExternalOutcome`, контроллер авторизации с `CurrentUserService` и репозиториями.

Дополнительные требования, найденные базлайном (фиксируются как задачи переноса):

1. `TelegramClientManager.closeAll()` не вызывается из main — при 13.3 нужен явный owner жизненного цикла (shutdown hook сборки), иначе TDLib-контексты переживают graceful stop.
2. Аватары Telegram, сохранённые в attachment storage, не удаляются при disconnect/удалении канала — в контракт вложений (п.7 Stage 13) входит очистка «сохранённых платформенных ресурсов» с владельцем.
3. Мёртвые места SPI не переносить без решения: `handle(SyncClientAccountCommand)`, `IChannelEventHandler/ChannelEventHandler`, `IPlatformEventPublisher`, `TelegramChannelAccountService.restart` — в 13.2a либо удалить, либо обосновать.
4. Фактические маршруты авторизации — `/api/channels/{channelAccountId}/telegram/*` (9 ручек), отдельных `/telegram/*` нет; web-адаптер 13.3 сохраняет именно их.
5. `spring.telegram.client.*` (api-id/api-hash/encryption-key) — константы в `application.yml`, env-переменных нет; сессии TDLib лежат в `./data/telegram` относительно CWD. Перенос не меняет пути (п.13.3 Stage 13) и требует вынести ключи в настройки до релиза коннектора.
6. `clientbus.kafka.outbound.enabled-channels` перечисляет WHATSAPP/VK без коннекторов; `springdoc` сканирует telegram-пакет в общих ресурсах — убирается по 13.3 (server без Telegram собирается и описывается без него).
7. UI-жёсткость: `NewConversationDialog` фильтрует TELEGRAM, `ChannelsPage` дёргает telegram-маршруты для любого канала — объём 13.7.

## 2. Контракт integration/dikidi (v0.1, к уточнению имён в 13.4)

Модуль не зависит ни от чего в репозитории (ни server, ни common, ни crm/api, ни connector/api). Публичные типы — собственные, предлагаемые имена:

| Тип | Назначение |
|---|---|
| `DikidiAccess` | connectionId, credentialVersion, login, ссылка на секрет, открываемый серверным портом на время операции; cookie/CSRF наружу не отдаются |
| `DikidiRequest` | method, URI, form, ajax-признак, `DikidiProfile` (Origin/Referer/bootstrap-контекст), monotonic deadline |
| `DikidiResponse` | status, body, retryAfter |
| `DikidiFailure` | AUTH_REQUIRED, ACCESS_DENIED, RATE_LIMITED(+retryAfter), TEMPORARY, INVALID_RESPONSE, CONTEXT_MISMATCH, CONNECTION_BUSY, DESTINATION_FORBIDDEN |
| `DikidiClient` | `execute(access, request)`; один экземпляр на сборку, создаётся app assembly; `close()` вызывает только владелец-сборка |
| `DikidiSessionPool` | ключ `connectionId + credentialVersion`; одиночный вход на ключ; ёмкость и eviction по времени жизни (перенос текущих 128/20мин) с тестом вытеснения активной CRM-сессии чатовой |
| `DikidiCallClass` | INTERACTIVE, SYNC, BULK — класс бюджета (п.3) |

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
| Отправка / отметка прочтения / обновления / вложения | НЕ ПРОВЕРЕНО | изменяющие запросы намеренно не выполнялись (13.6, выделенные данные, согласованный адресат) |

## 4. Прогоны-доказательства 13.1

Класс-пробник: `server/src/test/java/kit/penny/clientbus/crm/dikidi/CrmDikidiChatReadAcceptance.java` — opt-in (`clientbus.crm.acceptance.live=true`), только GET + два POST входа, жёсткий allowlist адресов, бюджет 40 вызовов, вывод без PII (структура, счётчики, имена полей, обезличенные извлечения). Проект `f024d60c-4d37-4f6c-8431-ff92c0411248`.

| Лог | Содержание |
|---|---|
| `server/target/crm-13-1-chat-probe-run2.log` | зелёный прогон после discovery (16 вызовов, writes=0) |
| `server/target/crm-13-1-chat-probe.log` | финальный прогон с именами полей (16 вызовов, writes=0) |
| `server/target/crm-13-1-chat-probe-noshell.log` | контроль: без shell-визита C02 = 400 на всех вариантах referer |
| `server/target/crm-13-1-chat-probe.log` (первый запуск) | исходное 400, приведшее к открытию предусловия |

## 5. Минимальный клиентский сценарий против дополнений

База этапа (после 13.4–13.5): bootstrap (вход+shell), список диалогов, непрочитанное как invalidation-сигнал, история C05 с `last`-курсором и epoch-датами, направление по `out`, автор внешних исходящих без Employee. Всё остальное — отправка, отметки прочтения, обновления/стриминг, вложения, поиск контакта, создание диалога — включается только после отдельных подтверждений (13.6) и не входит в минимальный контракт.

## 6. Открытые вопросы к 13.2

1. Форма `users[]` C04 и источник канонических участников: скалярный ID vs объект; нужен разбор реального payload-образца перед закреплением портов идентичности.
2. Классификация служебных диалогов (поддержка DIKIDI): структурный маркер утрачен — искать среди полей C02/C04/отдельного endpoint либо принимать явным allowlist-правилом пользователя.
3. Множественность диалогов на клиента и область chat user ID — требуют второго тестового проекта или многостраничных данных.
4. Семантика `time`-курсора C02 и таймзон epoch — уточнить при появлении больших выборок.
5. Изменяющие операции (send/read-mark): семантика ответа, echo-подавление, UNKNOWN-сверка — только 13.6 на согласованных данных.
