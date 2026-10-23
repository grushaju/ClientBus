# Stage 11.2 — Контракты и модель Notification

Дата: 21.10.2026. Основание: принятые [семантика и R01–R07](NOTIFICATION_SEMANTICS.md), [Stage 11](<Stage 11.txt>).
План 11.2 согласован пользователем. Дополнение пользователя: новые таблицы создаёт Hibernate `ddl-auto=update`; новые миграции не требуются. Коммиты не создавать.

Уточнение пользователя по коннекторам: Telegram — обычная реализация IChannelConnector; общие команды выбирают реализацию через registry. Notification не добавляет Telegram capabilities, scopes, права или отдельную маршрутизацию к context owner.

## 1. Артефакты и граница этапа

- [OpenAPI 3.1.1](server/src/main/resources/notification-contract.openapi.json) — целевой контракт 11 операций; файл не регистрирует endpoints.
- [Java DTO](common/src/main/java/kit/penny/clientbus/common/dto/notification) и [enum/types](common/src/main/java/kit/penny/clientbus/common/notification).
- [TypeScript](ui/src/api/types/notification.ts), [SSE](ui/src/api/types/uiEvent.ts), [history](ui/src/api/types/historySyncOperation.ts), [installations](ui/src/api/types/pushInstallation.ts).
- [Внутренние контракты](server/src/main/java/kit/penny/clientbus/server/notification/contract) для UI routing, origin/actor, send attempt, history completion и PushProvider. Они не сериализуются браузеру. Бизнес-вызовы коннекторов используют существующий [IChannelConnector](server/src/main/java/kit/penny/clientbus/server/connector/IChannelConnector.java).
- [JPA-модель](server/src/main/java/kit/penny/clientbus/server/persistence/entity/notification) и [параметры](server/src/main/java/kit/penny/clientbus/server/config/properties/NotificationProperties.java).

В 11.2 добавляются типы и схема, без рабочих SSE/controller/relay/push worker. Флаги creationEnabled, sseEnabled, pushEnabled, historySubmissionEnabled по умолчанию false. Они управляют соответственно созданием новых персональных уведомлений, SSE, запуском push-попыток и приёмом новых history-команд. REST чтения/прочтения центра, status/discovery и повтор уже принятого history key остаются доступны при выключении флагов с обычной ACL. Уже принятые операции завершаются, watchdog и cleanup продолжают работать. Outbox relay обслуживает принятые записи независимо от этих флагов. Отключение push приостанавливает попытки без продления TTL; при возобновлении просроченные задания завершаются EXPIRED. Отключение creation не отключает закрытие старых alerts и не откладывает новые уведомления на последующее включение. Флаги не являются общим выключателем persistence. Существующие API, Kafka DTO и UI history flow сохраняют совместимость.

Hibernate сканирует новые @Entity при обычном старте и создаёт таблицы/индексы/FK/CHECK. Проверка схемы выполняется тем же `hbm2ddl.auto=update` на отдельной PostgreSQL 16. Flyway V0 и текущее управление бизнес-таблицами не меняются. Domain UUID хранятся как ссылки для policy, без каскадного удаления старых фактов при удалении сотрудника/target; внутренние зависимости новых таблиц имеют FK.

## 2. Публичный транспорт и REST

Все endpoints требуют Bearer JWT. JWT не передаётся в URL. Private REST/SSE используют `Cache-Control: no-store`. Organization/recipient/actor вычисляет BE. Ответ отсутствующей или недоступной персональной записи — одинаковый 404 без target detail. Известные безопасные error codes перечислены в OpenAPI; технические исключения, token и provider response не выдаются.

| Операция | Контракт |
|---|---|
| GET /api/events?workspaceId=… | SSE текущего доступного W; персональные notification/access сигналы не ограничиваются выбранным W |
| GET /api/notifications | Все свои доступные записи; unreadOnly=false, limit=30 (1–100), pageCursor |
| GET /api/notifications/summary | unreadCount по той же ACL, что список |
| GET /api/notifications/{id} | Свой текущий доступный DTO, типизированный target, readAt, alertExpiresAt и вычисленный alertActive |
| PUT /api/notifications/{id}/read | 200 с DTO; повтор сохраняет первоначальный readAt |
| PUT /api/notifications/read-all | Без body, обязательный Idempotency-Key; scope ALL_ACCESSIBLE по всем W; 200 с readAt/updatedCount |
| PUT /api/push/installations/{installationId} | 200 с публичным состоянием/версиями и ETag registrationVersion; секреты только во входе |
| DELETE /api/push/installations/{installationId} | Свой recipient, proof в X-Installation-Secret и If-Match registrationVersion; 204 |
| POST /api/conversations/{id}/history-sync | Body beforeExternalId (null или ≤256 символов), limit 1–100; Idempotency-Key; 202 с DTO и Location после commit |
| GET /api/history-sync-operations/{id} | Своя operation + текущий доступ к диалогу; статус/безопасный итог |
| GET /api/conversations/{id}/history-sync-operations | Свои записи, activeOnly=true, keyset cursor/limit; active-only discovery дополняет повтор POST |

Idempotency-Key — UUID, уникальный для нового нажатия, сохранённый клиентом до отправки. Срок безопасного автоматического повтора — 7 дней. Ключ ограничен Organization/actor/видом команды, requestHash включает все семантические параметры. Read-all имеет фиксированный глобальный scope; history hash включает conversationId, нормализованный beforeExternalId и limit. Повтор с иным hash — 409 IDEMPOTENCY_CONFLICT; перед возвратом результата права проверяются заново.

После объявленного окна клиент не повторяет старую команду автоматически. После очистки сервер может не отличить неизвестный старый UUID от нового; бесконечная идемпотентность/обязательный IDEMPOTENCY_EXPIRED для уже удалённого ключа не обещаются. Новый запуск требует нового явного действия пользователя. До конца окна сохраняется вся связь ключа с terminal operation/result.

Пагинация DESC по (createdat, id), SQL-предикат следующей страницы `(createdat, id) < (:createdAt, :id)`. Cursor — opaque versioned base64url JSON до 1024 символов, привязанный к actor/Organization/query (включая unreadOnly или conversationId/activeOnly); несовпадение контекста — INVALID_CURSOR. Cursor не даёт прав, параметры всегда проходят серверную ACL. Отдельная подпись не нужна, если изменение cursor влияет только на границу доступной выдачи. ACL может изменить состав страниц между чтениями; замороженный snapshot не обещается.

SSE `event:` равен envelope.type; `data:` — UiEventEnvelope. SSE id/Last-Event-ID/replay не используются. SchemaVersion=1; unknown type запускает ограниченную сверку; несовместимая версия включает безопасный fallback. Неизвестные поля клиент игнорирует. `stream` заполнен только у stream.ready, `resource/workspaceId/correlationId` могут быть null. В ready — streamId, alertsAfter, heartbeatSeconds=15. Очередь до ready/reconciliation ограничена; overflow требует resync.

## 3. Новые таблицы Hibernate

| Таблица | Назначение и основные ограничения |
|---|---|
| notificationoccurrence | Уникальность (organizationid, occurrencekey). Origin=LIVE; UUID[] первоначальных получателей, включая пустой массив; immutable decision. dedupuntil переживает очистку центра |
| notificationdedup | Уникальность (organizationid, recipientemployeeid, deduplicationkey); FK occurrence. Не удаляется вместе с записью центра |
| notification | Один row на dedupid; FK dedup. Recipient/org/workspace/type/типизированный target, безопасный JSON object args, read/alert/retention. Индексы recipient pagination/unread, workspace, retention |
| notificationcommand | Read-all command: уникальность org/recipient/key, requesthash, scope, сохранённые readat/updatedcount/retainuntil |
| notificationoutbox | Уникальный eventid, destination/type/schema/payload JSON object ≤64 KiB, status/attempts/claimversion/lease, publishedat, operationid при наличии |
| pushinstallation | Owner/org, platform/provider/app/environment, зашифрованный endpoint, SHA-256 fingerprint, ownership hash, binding/endpoint versions, permission/activity. Уникальный активный endpoint |
| notificationdelivery | FK notification/registration; UNIQUE(notificationid, registrationid, bindingversion, channel). MOBILE_PUSH, версии попытки, claim/lease/expiry/result/error |
| historysyncoperation | Уникальность org/initiator/key, request hash, scope/cursor/limit, состояния/claim/deadline/result/error/retention; индексы discovery/watchdog/lease |
| outboundsendcallback | Корреляция раннего результата connector по channelaccountid/recipientexternalid/pendingexternalid; привязка messageid/sendattemptid, terminal outcome и ограниченное хранение |

Имена объектов этой подсистемы задаются строчными буквами без подчёркиваний: таблицы, колонки, индексы, PK/UK/FK и CHECK. Например: `historysyncoperation`, `notificationoutbox`, `claimversion`, `notificationoutboxpollidx`. Имена PK имеют вид `<таблица>pk`; именованный unique(id) используется Hibernate для имени PK без отдельного дублирующего unique index. `NotificationSchemaNaming` задаёт имена автоматически генерируемым enum CHECK до экспорта схемы через стандартный Hibernate Integrator. Java-поля и публичные DTO используют прежний camelCase.

Для уже созданной локальной БД смена mapping требует разового переименования существующих объектов: `ddl-auto=update` не выполняет rename старых таблиц и колонок. Новая схема и повторный Hibernate update проверяются отдельно. Миграции и изменения пользовательской БД в этот рефакторинг не входят.

Колонка `createdat` (Java-поле `NotificationRecord.createdAt`) заполняется PostgreSQL `clock_timestamp()`, возвращается ORM после INSERT; это не порядок commit. Только поля текущего состояния обновляются; первоначальные identity/audience/scope имеют updatable=false. Это ORM-ограничение не заменяет условные SQL-переходы и policy.

Recipient/org поля дочерних записей должны совпадать с occurrence/dedup и первоначальным решением; создание всех записей выполняется доверенным сервисом в одной транзакции. В 11.2 не заявляется уже работающий writer либо защита от произвольного SQL администратора.

Очистка идёт ограниченными batch: terminal delivery → notification → dedup → occurrence, с независимыми сроками; FK запрещает удалить ещё используемый факт. Outbox PENDING/RETRY/IN_FLIGHT/QUARANTINED не очищается по сроку опубликованных записей. Удаление установки допускается после отмены/очистки её delivery; endpoint/proof никогда не возвращаются в DTO и не логируются.

Активная уникальность endpoint задаётся (provider, appid, environment, activeendpointfingerprint). Для enabled=true active fingerprint равен endpoint fingerprint; для enabled=false значение NULL, поэтому несколько неактивных регистраций могут хранить прежний fingerprint. Ротация увеличивает endpointVersion; rebind — bindingVersion. Ошибка старого endpoint и старый logout не изменяют новую версию.

## 4. Идентичность и транзакционные переходы

**R01 / источник:** Runtime использует NotificationIdentity.incoming(channelAccountId, conversationId, externalMessageId) — SHA-256 length-prefixed UTF-8 полей с N2/v2; occurrence дополнительно ограничен Organization. Conversation входит в ключ, поскольку внешний ID сообщения может повторяться в разных чатах одного аккаунта; это соответствует UNIQUE(conversationid, externalid) сообщения. Двухаргументный N2/v1 helper сохранён для совместимости контракта, но runtime writer его не использует. Для N3 ключ включает messageId + sendAttemptId. N1 использует устойчивое occurrence изменения назначения; N4 — incident ID до восстановления, N5 — operationId + terminal outcome. SourceEventId/outbox eventId/Kafka offset не заменяют business key.

Первое допустимое LIVE-возникновение сериализуется с назначением через lock строки диалога. INSERT occurrence ON CONFLICT DO NOTHING и первоначальная аудитория, уведомления/dedup/delivery/outbox фиксируются одной транзакцией. При конфликте не пересчитывать аудиторию и не дописывать новые recipient. Import/SYNC не вставляют LIVE decision. Повтор LIVE после переназначения использует прежнее решение; новый сотрудник не получает вторую N2. Наличие уже импортированного message row не подавляет первую LIVE decision.

NotificationSourceContext создаётся на доверенной BE-границе: origin, firstObservedAt, sourceEventId, actor (null у системы). Повторы сохраняют первоначальные origin/firstObservedAt. Первичные источники старше поддержанного окна и административный replay подавляют новые персональные эффекты; уведомления не выводятся из факта отсутствия dedup после его очистки. Возраст не подменяет origin. Origin внедряется во все импортные пути до включения N2.

**R02 / отправка:** OutboundAttemptContext содержит messageId/sendAttemptId. Ручной retry создаёт новый attempt в транзакции; технический повтор сохраняет его. В 11.8 attempt проходит через Kafka-команду, SendMessageCommand и ConnectorSendResult. Claim/release, terminal failure и platform result проверяют attempt под lock строки сообщения; результат применим только к этому attempt и допустимому состоянию. PlatformMessageEvent/Kafka payload также сохраняют optional sendAttemptId; событие без него может менять только legacy-строку без идентифицированной попытки. Notification не обращается к внутренним классам платформы, Telegram остаётся обычной реализацией IChannelConnector.

Ранний внешний callback хранится в небольшом журнале outboundsendcallback по (channelAccountId, recipientExternalId, pendingExternalId). Регистрация отправки связывает ключ с конкретным messageId/sendAttemptId и применяет уже полученный результат в той же транзакции. Неизвестный callback не прикрепляется к текущей попытке по одному messageId. Журнал не отправляет команды: срок хранения семь дней, не более 4096 ещё не связанных результатов, очистка batch 256 с SKIP LOCKED. Сериализация ключа и lock строки защищают гонку callback/registration/cleanup. Удаление временных файлов выполняет сам connector по локальному after-commit сигналу завершения и только для соответствующего attempt.

Время первого наблюдения N1/N3/N4 хранится в доменной строке вместе с assignmentOccurrenceId/sendAttemptId/incidentId и не обновляется при повторе того же ID. Writer читает именно это время; legacy NULL не создаёт новое персональное уведомление. Для N2 доверенная граница — исходный Kafka envelope occurredAt/eventId; технический retry сохраняет его. Envelope без времени/ID сохраняет бизнес-сообщение без персонального эффекта. Административный replay сохраняет исходный envelope либо помечается SYNC; пересоздание LIVE с новым временем не является техническим retry.

**Read-all:** один actor/key получает право выполнить команду через INSERT ON CONFLICT; конкурентный повтор ждёт исходную транзакцию и читает её результат. RequestHash проверяется до возврата. Один UPDATE доступных непрочитанных строк устанавливает одно DB readAt и возвращает count; result command + notifications.invalidated outbox фиксируются в той же транзакции. После commit повтор только возвращает result. Не строить границу из max(id), createdAt или времени открытия списка; важен SQL snapshot UPDATE. Policy-предикат общий для list/count/read/read-all и выполняется в БД.

**Claim:** короткая транзакция выбирает due rows FOR UPDATE SKIP LOCKED, переводит в IN_FLIGHT/RUNNING, увеличивает claimVersion и attempts, задаёт leaseUntil. Внешний вызов выполняется вне этой транзакции. Результат обновляет row только с тем же id/claimVersion/ожидаемым состоянием. Новый владелец после expiry увеличивает version; поздний ack не завершает его работу. Lease не гарантирует единственный внешний вызов.

| Ресурс | Переходы |
|---|---|
| Outbox | PENDING/RETRY → IN_FLIGHT → PUBLISHED после Kafka ack; transient/unknown → RETRY, permanent payload/authorization/configuration error → QUARANTINED; takeover истёкшего IN_FLIGHT даёт новый claim |
| Delivery | PENDING/RETRY → IN_FLIGHT → PROVIDER_ACCEPTED; transient → RETRY; permanent → FAILED, stale binding/ACL/read/category → CANCELLED, elapsed TTL → EXPIRED |
| History | PENDING → RUNNING → SUCCEEDED/FAILED; takeover RUNNING только с новой claimVersion; deadline/watchdog/DLQ дают FAILED условно; terminal immutable |

Карантин наблюдаем и не блокирует очередь. Ручной повтор outbox сохраняет eventId и первоначальный бизнес-смысл; исправление payload допускается только через контролируемую совместимую процедуру, а не подмену события. Для command outbox перед повтором проверяются status/deadline операции. Kafka timeout с неизвестным ack не считается доказанной постоянной ошибкой. Опубликованное событие не возвращается в PENDING обычным retry.

**R03 / ACL:** policy получает явный subject; DTO/SSE/push проверяют текущие права по БД, допустимы пакетные запросы вне Kafka consumer socket loop. Отказ policy/БД закрывает чувствительную выдачу. Минимальная очистка прежнего scope не требует существования удалённого target. Очередь/кэш не являются разрешением; уже отправленные данные отозвать нельзя.

**Закрытие alert:** notification.alertInvalidatedAt устанавливается один раз при прекращении актуальности конкретного occurrence и никогда не очищается. Доменный переход и UPDATE ... WHERE alertinvalidatedat IS NULL фиксируются в одной транзакции с invalidation outbox; восстановление канала закрывает N4 предыдущего incident, новое падение создаёт новый incident/occurrence. Аналогично закрываются прежнее назначение и прежняя send attempt. Центр и readAt сохраняются. alertActive требует NULL alertInvalidatedAt, актуальную категорию/ACL, readAt=NULL и неистёкший TTL. ORM-метод не позволяет сбросить маркер; условный SQL и сериализация с domain writer обязательны при реализации 11.8.

**R04 / alert:** gateway берёт alertsAfter из PostgreSQL clock_timestamp() для новой stream-сессии до ready. SSE-баннер допустим только после initial reconciliation, для created с DTO.createdAt > alertsAfter, readAt=null, alertActive=true, будущим alertExpiresAt и отсутствием id в сохраняемом ограниченном dedup аккаунта. Event.occurredAt не используется как курсор. Created до границы подавляется даже при поздней Kafka-доставке; committed после границы row с более ранним createdAt может остаться без баннера. Это допустимый пропуск, центр остаётся доступен. Граница не отбрасывает invalidation/REST recovery и не меняет read-all.

Client dedup хранит только opaque notificationId/expiry в пределах аккаунта, очищается при logout вместе с аккаунтными данными, ограничен 48 часами и 4096 записями. Он общий для SSE и foreground push; проверка/пометка выполняются до показа. Eviction/очистка хранилища и несколько вкладок не дают exactly-once гарантии. Background OS push имеет собственный жизненный цикл и TTL; SSE-граница не отменяет уже созданные серверные push intents.

## 5. History, общий коннектор и совместимость

HistorySyncCommand задаёт operation/initiator/org/W/conversation/channel/request/origin/deadline; worker захватывает operation и передаёт claimVersion результату HistorySyncCompletion. Успех содержит importedCount и historyStartReached после фиксации данных. Массив полных MessageDto в operation не хранится: после SUCCEEDED UI перечитывает обычный REST-кэш сообщений. Partial import допустим при FAILED, остаётся HISTORY_IMPORT. Result не читается через чужой operationId.

Потеря 202: клиент повторяет исходный POST с сохранённым ключом, получает ту же operation включая terminal state. Active-only discovery не заменяет этот путь. При недоступном target возвращается безопасный отказ, даже если operation существует. Срок result/command связи не короче окна safe retry.

До 11.10 текущий POST /api/messages/conversation/{id}/history/sync продолжает возвращать старый Result с messages/historyStartReached. Новый API и UI переключаются совместно feature flag; в один запрос нельзя без объявления подменить 200 синхронный результат на 202 operation. В существующий Kafka history topic новый payload не публикуется до обновления всех его consumers и согласованного schemaVersion. Во время переключения legacy/new processing различаются явно; старый consumer не должен получить новый DTO через прежний type header. Rollback flag оставляет status/read API доступным для уже принятых операций.

Команды history/send/read/profile проходят через существующий ChannelConnectorRegistry → IChannelConnector. Telegram выбирается по supports(ChannelType) наравне с другими реализациями. Notification использует доменные изменения и общие результаты; UI/notification DTO, ACL и аудитории не зависят от классов Telegram/TDLib. У коннектора нет дополнительных Notification capabilities/scopes. Публичный контракт IChannelConnector не расширяется платформенными auth-step или управлением owner.

Локальные context и lifecycle конкретной платформы остаются внутри реализации. Недоступность коннектора обрабатывается общим command/operation flow (CONNECTOR_UNAVAILABLE), а SSE fanout не обещает HA коннекторов. Добавленный ранее, нигде не использованный ConnectorOwnerResolver удалён по уточнению пользователя; отдельный owner-route/ownershipVersion не является требованием Stage 11. Существующий интерактивный HTTP flow подключения проверяется общей policy канала; новая специальная Telegram capability для него не создаётся.

## 6. Installation и provider

installationId — случайный UUID, installationSecret — CSPRNG минимум 32 байта в base64url (43–128 символов). Клиент сохраняет proof в защищённом хранилище установки; BE хранит SHA-256 hash proof и сравнивает constant-time. Provider reference не подтверждает владение устройством. Первое создание authenticated installation привязывает owner из JWT; любое обновление существующей installation требует proof. Смена аккаунта допустима на той же установке только с proof, с новым bindingVersion и отменой прежних заданий.

Создание PUT требует expectedRegistrationVersion=0 и отсутствия installationId. Существующая установка требует proof и точного expectedRegistrationVersion; обычный update действует от текущего owner, явный rebind привязывает нового owner из JWT. Любое изменение, включая DELETE, permission/activity, endpoint, rebind и worker disable, атомарно увеличивает registrationVersion (JPA @Version либо условный SQL). DELETE требует текущего owner, proof и If-Match registrationVersion, делает soft disable. При конфликте — 409 INSTALLATION_CONFLICT без изменения; при конфликте версии после проверки proof ответ возвращает текущий ETag для нового действия активной сессии. Клиент сериализует изменения установки, сохраняет версию и отбрасывает запросы старой account session; автоматически повторять старое тело с обновлённой версией запрещено. Потерянный ответ на создание также даёт конфликт при повторе 0; новое действие текущей сессии с полученной версией восстанавливает регистрацию. PUT/DELETE проверяют доказательство и версию; offline/поздний logout не отвязывает новый binding. Совпавший endpoint другой установки возвращает INSTALLATION_CONFLICT; токен сам по себе не переносит чужую installation. AppId/provider/environment проверяются по серверному allowlist. APNS разрешён только IOS. OS permission — подсказка; DENIED не даёт оснований обещать показ и подавляет новые разрешаемые попытки до обновления регистрации.

Provider reference шифруется authenticated encryption на BE, key хранится вне БД; persisted encryptedEndpoint включает nonce/tag и версию ключа. Fingerprint — SHA-256 reference. Продакшен encryption/provider adapter выполняется в 11.8/mobile integration; в 11.2 secrets не пишутся рабочим writer в БД. Не логировать request DTO/body/proof headers.

Один логический delivery на notification/registration/binding/channel. Перед каждой попыткой: ACL, read/alertActive, expiresAt, enabled/activity/binding и актуальный endpointVersion. Внешний request содержит notificationId/schemaVersion, app/environment/platform, registration reference, остаток TTL; адаптер формирует общий текст без target/переписки. ACCEPTED не означает доставку/прочтение. INVALID_ENDPOINT отключает регистрацию только условно по использованным binding/endpoint version и fingerprint. INVALID_PAYLOAD не отключает token автоматически.

Terminal delivery атомарно получает completedAt и retainUntil=completedAt+deliveryRetention; до terminal оба поля NULL. Очистка выбирает только terminal rows по retainUntil. expiresAt задаёт крайний срок отправки и не заменяет время завершения. CHECK запрещает terminal без обоих времён и retention у незавершённого задания.

Delivery expiresAt ≤ alertExpiresAt, retry не продлевает TTL. Если Retry-After выходит за TTL, работа завершается EXPIRED. Ротация endpoint при прежнем binding допускает следующую попытку на новой версии, без нового логического задания. Если INVALID_ENDPOINT пришёл после ротации, версия регистрации проверяется под блокировкой до terminal-перехода: актуальная попытка получает RETRY на новом endpoint либо EXPIRED по исходному TTL. Ошибка старой версии не завершает такое задание как FAILED. Регистрация и delivery блокируются в порядке installation → delivery, как при logout/rebind. Уже ACCEPTED не повторяется из-за отсутствия read. Разрыв после send до DB ack допускает повтор push. Настоящие FCM/APNs и устройства остаются отдельной интеграцией.

## 7. Начальные конфигурируемые параметры

| Параметр | Значение |
|---|---|
| creationEnabled / sseEnabled / pushEnabled / historySubmissionEnabled | false / false / false / false |
| sourceReplayWindow / dedupRetention | 30 дней / 90 дней |
| centerRetention | 30 дней |
| commandIdempotencyWindow / historyIdempotencyWindow | 7 дней / 7 дней |
| historyRetention | Не менее 7 дней после terminal; никогда не короче окна ключа |
| deliveryRetention / publishedOutboxRetention | 7 дней после terminal/published; незавершённые не удаляются по этому правилу |
| installationActivity | 30 дней от lastSeenAt |
| N1 / N2 / N3 / N4 / N5 alert TTL | 24 часа / 5 минут / 24 часа / 24 часа / 1 час (N5 не активирован) |
| clientAlertDedupRetention | 48 часов, максимум 4096 opaque IDs на аккаунт |
| lease / historyDeadline | 30 секунд / 2 минуты |
| maxEventBytes / maxOutboxBytes | 16 KiB / 64 KiB |
| queueCapacity / outboxBatchSize / pushBatchSize | 128 / 100 / 50 |
| heartbeatSeconds | 15 |

Префикс clientbus.notifications; record с @DefaultValue проверяет положительные значения и отношения окон/lease/размеров при binding. MaxEventBytes/MaxOutboxBytes можно уменьшить; увеличение сверх 16/64 KiB требует новой версии контракта/схемы и отклоняется при binding. При уменьшении retention уже принятый ключ не теряет прежнего обещанного окна: retainUntil хранится в row. Изменение defaults не сокращает существующие expiresAt/retainUntil автоматически. Эти значения — стартовая конфигурация, не измеренные SLO; конфигурация соединений, HTTP-бюджет, fallback/control/retry и проверенный профиль нагрузки 11.7 описаны в [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md). UI-параметры VITE_UI_RECOVERY_* задаются при сборке отдельно от BE properties. Новых wire DTO/endpoints или таблиц для 11.7 не требуется.

## 8. Проверка и следующие этапы

[BE contract tests](server/src/test/java/kit/penny/clientbus/server/notification/NotificationContractTest.java) проверяют Java/OpenAPI, enum/reference integrity, сериализацию unknown SSE type, redaction, validation, key identity и config bounds.
[PostgreSQL schema tests](server/src/test/java/kit/penny/clientbus/server/notification/NotificationSchemaTest.java) создают схему Hibernate update, повторяют update, проверяют ORM round trip, DB unique/FK/CHECK и условные SQL-протоколы.
[UI contract tests](ui/tests/notificationContracts.test.cjs) сверяют TS properties/nullability/enum с OpenAPI и компиляцию типов.

Это проверки контрактов/схемы; выполнение SQL fixture не считается реализацией бизнес-сервиса/ACL/relay либо прохождением T01–T30 целиком. SSE, REST controllers, outbox relay, center UI и push delivery реализуются в 11.3–11.10 по отдельно согласованным планам.

Результат 21.10.2026: 11.2 выполнен по согласованному плану с уточнением Hibernate update вместо миграций. Коммиты не создавались; 11.3 выполнен по согласованному плану; результат — NOTIFICATION_ACCESS.md.

| Проверка | Результат |
|---|---|
| NotificationContractTest | 8 passed |
| NotificationSchemaTest, отдельная PostgreSQL 16 / Testcontainers / Hibernate update | 12 passed; повторный update и ORM round trip включены |
| MessageInboundFlowIntegrationTest, полный Spring-контекст | 3 passed |
| npm test | 179 passed, включая 3 новых TS/OpenAPI checks |
| BE compilation / npm run build / UI resources | Passed; index.html в ui/dist и server/target/classes/static совпадает |

Источники формата/SQL: [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html), [PostgreSQL constraints](https://www.postgresql.org/docs/16/ddl-constraints.html), [READ COMMITTED snapshots](https://www.postgresql.org/docs/16/transaction-iso.html).

Повторная проверка после согласованного ревью 21.10.2026: 23 BE-теста (8 contract + 15 schema) и 3 UI contract-теста прошли. Добавлены SQL-проверки CAS при смене аккаунта/ротации/logout, terminal retention и необратимого закрытия alert. Это проверки модели и условных переходов; HTTP writers и runtime lifecycle будут реализованы на последующих этапах.

Этап 11.3 согласован и реализован: общая актуальная ACL, аудитории, безопасная маршрутизация и проверки установки описаны в [NOTIFICATION_ACCESS.md](NOTIFICATION_ACCESS.md). Транспорт и endpoints включаются на последующих этапах.

Уточнения повторного ревью 11.3: неизвестный тип/версия события после проверки распознанной аудитории даёт минимальный resync без target; FORMER_SCOPE сохраняет cleanup. При повторе создания установки с expectedRegistrationVersion=0 проверяется proof существующей строки и возвращается 409 с текущим ETag, без mutation. Внутренний VersionConflict переносит только версию, без owner/endpoint/proof. Отображение в HTTP выполняется при реализации endpoint 11.8. Общая HTTP ACL Workspace/Conversation использует только актуальную policy, без повторной проверки cached assignment.

Этап 11.4 реализован 22.10.2026: [NOTIFICATION_TRANSPORT.md](NOTIFICATION_TRANSPORT.md). GET /api/events отмечен feature-flagged в OpenAPI и реализован за sse-enabled=false; остальные endpoints остаются contract-only. JSON errors до открытия соответствуют NotificationApiErrorDto. 70 BE-тестов и 191 UI-тест прошли, TypeScript/Vite build успешны. Бизнес-вертикаль подключена в согласованном этапе 11.5: NOTIFICATION_OUTBOX.md.

Результат 11.5 (22.10.2026): транзакционный UI outbox/relay с claim fencing, retry/quarantine и метриками, propagation origin, глобальный provider и REST-обновление Inbox/переписки. Настоящий desktop/mobile browser E2E пройден; подробности и границы следующего этапа — [NOTIFICATION_OUTBOX.md](NOTIFICATION_OUTBOX.md). Миграций и коммитов нет. 11.6 согласован и реализован; протокол — NOTIFICATION_UI.md.

Ревью 11.5 закрыто: отмена/deadline REST и late-response guards, ожидание свежего Inbox fetch и retry, отдельный default scheduler, независимые метрики outbox с valid/age. Протокол исправлений и проверки — NOTIFICATION_OUTBOX.md. План 11.6 с учётом пяти замечаний ревью согласован пользователем 22.10.2026.

Этап 11.6 реализован 22.10.2026: [NOTIFICATION_UI.md](NOTIFICATION_UI.md). Добавлены поколения Auth/Workspace и отмена старых запросов, storage/REST/SSE auth lifecycle, немедленный access/identity control до ready, подписки метаданных диалога/групп/сохранённых фильтров, единый dirty для локальных и SSE invalidation. Смена A → B → A не возобновляет старые ответы; обычные обновления сохраняют черновики/прокрутку/локальные команды, скрытые ресурсы перечитываются при возврате. Повторное ревью закрыто: control отменяет ручные identity/Workspace запросы, memberships другого Workspace не сбрасывают черновик, assignment reconciliation дожидается команды и свежего GET, профиль/другие аккаунты клиента подписаны независимо. Проверки: 232 UI-теста (11 новых регрессий), TypeScript приложения/E2E, Vite build и 4 реальных desktop/mobile browser E2E прошли. Миграций и коммитов нет. Уточнённый план 11.7 принят; реализация и приёмка — NOTIFICATION_RECOVERY.md. Следующий 11.8 требует отдельного согласования.

Этап 11.7 выполнен 22.10.2026: [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md). Добавлены общая политика восстановления/редкой сверки/fallback, бюджет реальных GET, partial REST failure без разрыва SSE, bounded retry/Retry-After и hidden/offline lifecycle. Несовместимая версия терминально включает fallback, как требует контракт. Gateway дополнен fetch limits и безопасным lifecycle собственной consumer group. Проверки: 255 UI-тестов, 30 BE-тестов, TypeScript приложения/Vite config/E2E, production и rollback build, 8 настоящих desktop/mobile E2E прошли. Новых wire DTO/endpoints, миграций и коммитов нет. Флаги BE по умолчанию остаются выключены. 11.8 требует отдельного согласования.

Этап 11.8 закрыт 23.10.2026 по реализации и автоматической приёмке: N1–N4, центр BE/UI, read/read-all, lifecycle баннеров, push foundation и имена объектов БД без подчёркиваний. Все замечания завершающего ревью исправлены и покрыты регрессиями. Финал: 776/776 BE, 289/289 UI, 10/10 реальных desktop/mobile browser-сценариев на финальной схеме; TypeScript и production build прошли. Протокол — [NOTIFICATION_CENTER.md](NOTIFICATION_CENTER.md). Применение к существующей локальной БД требует отдельного разового rename старых объектов; пользовательская БД, staging и коммиты не изменялись. Следующий 11.9 требует отдельного плана и согласования.
