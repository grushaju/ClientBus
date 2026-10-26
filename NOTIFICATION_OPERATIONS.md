# Stage 11.11 — эксплуатация уведомлений и History Sync

Документ описывает реализованные диагностические средства и порядок включения. Предметные правила остаются в [NOTIFICATION_SEMANTICS.md](NOTIFICATION_SEMANTICS.md), API и события — в [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md). Детали восстановления браузера и gateway находятся в [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md), History — в [NOTIFICATION_HISTORY.md](NOTIFICATION_HISTORY.md), центра — в [NOTIFICATION_CENTER.md](NOTIFICATION_CENTER.md).

Приведённые здесь параметры метрик заменяют историческое описание двухсекундного outbox sampler в документе этапа 11.5. Числовые пределы настройки не являются обещанием производительности. Полный нагрузочный профиль, измерения p95, два gateway и итоговая приёмка относятся к 11.12.

## 1. Доступ к состоянию приложения

По HTTP открыты только Actuator endpoints `health` и `metrics`; остальные Actuator endpoints запрещены. Доступ возможен двумя способами:

- Обычный Bearer JWT действующего `SUPER_ADMIN`. Существующая проверка пользователя, enabled и роли в БД сохраняется. При отказе БД этот способ аутентификации недоступен.
- Отдельная операторская identity через HTTP Basic, настроенная внешними параметрами `clientbus.management.username` и `clientbus.management.password-hash` (env `CLIENTBUS_MANAGEMENT_USERNAME` и `CLIENTBUS_MANAGEMENT_PASSWORD_HASH`). Она проверяется в памяти и действует только для health/metrics; бизнес-API и выдача пользовательского JWT для неё недоступны. HTTP session не создаётся.

Оба management-параметра по умолчанию пусты: Basic выключен. При включении нужны оба; hash должен быть BCrypt `$2a$`/`$2b$`/`$2y$` с cost 4–16. Username — не более 128 символов, без `:`, управляющих символов и пробелов по краям. Ошибка конфигурации останавливает запуск без вывода значений credentials. Внешняя конфигурация хранит hash; клиент Basic передаёт исходный пароль. Реальные credentials не добавляются в репозиторий. Для удалённого доступа требуется TLS.

Для проверки локального процесса можно выполнить `curl.exe --user "$env:CLIENTBUS_MANAGEMENT_USERNAME" http://localhost:8080/actuator/health/liveness`: curl запросит пароль интерактивно. Пароль и Bearer-токен не передаются в URL, не сохраняются в операторской команде или отчёте. Операторская identity позволяет проверить liveness и прочитать кешированные метрики при отказе БД; readiness по-прежнему проверяет БД и при её отказе отвечает 503. Это не обходит DB-проверки пользовательских JWT.

Таблица использует стандартный base path `/actuator`. Endpoint matcher учитывает заданные management base path и servlet context path, включая management base path `/`; эти настройки не расширяют Basic-доступ на бизнес-API. Не публиковать management endpoints в публичном reverse proxy без предусмотренной аутентификации. Prometheus registry и отдельный telemetry collector этапом не добавлены.

| Запрос | Что проверяет | Как использовать |
|---|---|---|
| `GET /actuator/health/liveness` | Состояние процесса `livenessState` | Потеря Kafka не означает необходимость перезапуска процесса |
| `GET /actuator/health/readiness` | `readinessState` и основную БД `db` | Готовность основного HTTP API; доставка не входит в группу |
| `GET /actuator/health/delivery` | `uiPipeline` | SSE выключен: UP с `mode=disabled`; включён: `ready` или DOWN с `mode=degraded` |
| `GET /actuator/health` | Общий агрегат health contributors | Может стать DOWN из-за доставки; не подменяет явную readiness-группу |
| `GET /actuator/metrics` | Имена зарегистрированных meters | Набор зависит от флагов и произошедших событий |
| `GET /actuator/metrics/{name}` | Измерения и допустимые tags выбранного meter | Например, `clientbus.notification.outbox.pipeline.pending` с `tag=pipeline:ui` |

`delivery` отражает Kafka→gateway и доступность приёма SSE. Она не доказывает публикацию outbox, исправность History или доставку push. При Kafka outage readiness может оставаться UP, а delivery — DOWN. Чтение REST, центра и сохранённых History-операций продолжается при доступной БД и действующих правах; зависимые бизнес-команды могут отдельно требовать Kafka.

Если инфраструктурная проверка не умеет аутентифицироваться, её конфигурация должна быть отдельно согласована. Текущие endpoints намеренно не становятся публичными ради probes.

## 2. Семантика измерений

### Снимки общей БД

Сборщики работают независимо от `sse-enabled` и `push-enabled`, на выделенном scheduler. Чтение кешированных метрик не вызывает SQL или Kafka. Для HTTP scrape без обращения к БД используется отдельная management identity; пользовательский JWT требует обычной DB-проверки при аутентификации. Запросы сборщиков выбирают актуальные состояния, а не агрегируют весь архив опубликованных строк.

| Параметр `clientbus.notifications` | Default | Допустимые значения |
|---|---:|---|
| `metrics-poll-ms` | 10000 ms | 1000–60000 ms |
| `metrics-query-timeout-seconds` | 2 s | 1–10 s |

Снимок считается свежим не более `3 × metrics-poll-ms`, по умолчанию 30 секунд. Этот порог публикуется в `clientbus.notification.metrics.stale.after.seconds`. Ошибка немедленно устанавливает `sample.valid=0`; превышение порога также делает снимок недействительным. До первого успеха числовые значения могут быть `NaN`. После ошибки сохраняются прежние данные и растёт возраст снимка; это не новый нулевой backlog. Возраст старейшей ожидающей записи увеличивается между успешными выборками.

SQL/transaction timeout ограничивает выполнение запроса, но не является общим deadline получения соединения из pool. Параметры connection acquisition/socket timeout настраиваются отдельно для окружения. Последовательно исполняемые сборщики могут задержать друг друга; проверка возраста обнаруживает отсутствие нового снимка. Метрики не занимают поток relay или Kafka consumer.

| Метрики | Значение |
|---|---|
| `clientbus.notification.features.enabled{feature}` | Эффективные флаги экземпляра: `creation`, `sse`, `push`, `history_submission` |
| `clientbus.notification.outbox.pending`, `.quarantined`, `.oldest.seconds` | Общий outbox: PENDING/RETRY/IN_FLIGHT и отдельно QUARANTINED |
| `clientbus.notification.outbox.pipeline.pending`, `.pipeline.quarantined`, `.pipeline.oldest.seconds` с `pipeline=ui/history` | Разделение UI invalidations и History commands |
| `clientbus.notification.outbox.sample.valid`, `.sample.age.seconds` | Доступность и свежесть outbox-снимка |
| `clientbus.history.operations.active{status=pending/running}`, `.oldest.seconds` | Логические активные History-операции в БД |
| `clientbus.notification.push.pending`, `.inflight`, `.oldest.seconds` | Pending включает PENDING/RETRY/IN_FLIGHT; inflight — его подмножество. Складывать их нельзя |
| `clientbus.notification.state.sample.valid`, `.sample.age.seconds` с `pipeline=history/push` | Независимая доступность снимков History и push |
| `clientbus.notification.state.sample.errors{pipeline}`, `clientbus.notification.outbox.errors{stage=statistics}` | Ошибки попыток измерения |

DB gauges показывают общую БД и повторяются на каждом экземпляре. Их нельзя суммировать по экземплярам. Для обзора выбирать свежий валидный источник либо явно согласованную агрегацию валидных снимков. Флаги и runtime gauges относятся к экземпляру; различия флагов между экземплярами нужно видеть отдельно.

### Попытки, завершения и физические ресурсы

Counters и timers относятся к процессу, сбрасываются после рестарта и не являются журналом расчётов. Повторный вызов Kafka/провайдера увеличивает число попыток; он не создаёт новую уникальную бизнес-операцию. После commit наблюдение выполняется best effort: crash между commit и записью метрики может потерять наблюдение. Для точного состояния источником остаётся БД.

| Группа | Основные имена и смысл |
|---|---|
| Публикация | `clientbus.notification.publish.calls{pipeline=ui/history}`; `.publish.duration{pipeline,result=ack/failure}` — вызов publisher до ack/ошибки. Ack не подтверждает обработку consumer или UI |
| Outbox | `clientbus.notification.outbox.attempts{result=published/retry/quarantined}`, `.errors{stage}` — исходы попыток relay и ошибки БД |
| History | `clientbus.history.operations.created`, `.claims{kind=initial/takeover}`, `.completed{status,reason}`, `.duration{status}`, `.stale{stage}` — принятые и сохранённые переходы; duration от создания до terminal |
| Физический History worker | `clientbus.history.worker.capacity`, `.slots.occupied`, `.active`, `.admission.enabled`, `.offers{result}`, `.stale{stage}`; `clientbus.history.connector.calls`, `.duration` |
| History maintenance | `clientbus.history.maintenance.valid`, `.last.success.age.seconds`, `.duration{result}`; `clientbus.history.errors{stage}` |
| SSE ресурсы | `clientbus.notification.sse.connections` и `.connections.capacity`; `.queue.entries` и `.queue.capacity`; `.workers.active` и `.workers.capacity`; `.executor.queue.entries` и `.executor.queue.capacity`; `.closers.active`, `.closers.queue.entries` |
| SSE исходы | `clientbus.notification.sse.pipeline.ready`, `.admissions{result}`, `.closed{reason}`, `.resync{reason}`, `.queue.wait`, `.write.duration{kind,result}`, `.invalidation.age` |
| Kafka subscriber | `clientbus.notification.kafka.lag.records`, `.assigned.partitions`, `.sample.valid`, `.sample.age.seconds`, `.records{result}`, `.errors{stage}`, `.groups.cleaned` |
| Kafka History listener | Штатный `kafka.consumer.fetch.manager.records.lag.max{pipeline=history}` — отставание fetch position активного History consumer. Стабильный client ID начинается с `clientbus-history-operation`; binder удаляется вместе с consumer. Listener выключен — соответствующего meter нет |
| Центр | `clientbus.notification.created{type}`, `.read{scope}` — после commit. Прочтение центра не означает получение push устройством |
| Push persistence | `clientbus.notification.push.queued` — только реально вставленные intents после commit; `.transitions{status}` — сохранённый исход; `.stale` — отброшенный результат устаревшего claim |
| Push provider | `clientbus.notification.push.provider.calls`, `.responses{outcome}`, `.waits{result}`, `.duration`, `.active`, `.capacity`; отдельно `clientbus.notification.push.admission.enabled` и `.errors{stage}` — вызовы и ожидания отдельно от сохранённых переходов |
| Очистка | `clientbus.notification.cleanup.runs{result}`, `.duration{result}`, `.removed`, `.errors`, `.valid`, `.last.success.age.seconds`; `clientbus.notification.push.cleanup{resource}` |

`clientbus.history.worker.active` показывает выполняющиеся внешние вызовы, `.slots.occupied` — занятые/зарезервированные слоты. Операция может уже получить FAILED по deadline, а неотменяемый вызов продолжать занимать слот. Нулевое число RUNNING в БД не доказывает свободный worker. `maintenance.valid` и `cleanup.valid` описывают последнюю завершённую попытку; зависание обнаруживается также по растущему `last.success.age.seconds` относительно настроенного poll.

SSE queue entries — сумма очередей соединений экземпляра; queue capacity — предел одной очереди, не общий предел gateway. Lag считается на owner-потоке как сумма `max(0, endOffset − position)` назначенных партиций. Subscriber не commit-ит offsets: обычный lag по committed offsets для него не является правильной проверкой. Lag не включает ожидание SSE, REST и применения данных браузером; его валидность ограничена `gateway.pipeline-stale-after`, по умолчанию 10 секунд.

После отложенной отправки resync закрытие сохраняет причину `overflow` или `malformed`, а не записывается как `normal`. Если раньше фактически сработал timeout/error либо другое закрытие, учитывается только эта первая причина. Одно соединение увеличивает ровно один счётчик `.closed{reason}`.

`sse.invalidation.age` заканчивается успешной записью в сокет и зависит от согласованности часов. Она не измеряет обновление DOM. Push timeout может смениться поздним ответом внешнего вызова: provider response остаётся диагностическим фактом, а claim/lease защищают сохранённое состояние. При retry, превратившемся по TTL в EXPIRED, transitions показывает EXPIRED. `PROVIDER_ACCEPTED` не подтверждает получение ОС, открытие или прочтение пользователем.

При выключенном или ещё не запущенном History listener его Kafka lag недоступен, а не равен нулю. Native lag не измеряет выполнение операции: его сопоставляют с логическим DB backlog и физическими слотами worker.

Прикладные tags содержат ограниченные enum/классы исходов; native Kafka binder дополнительно имеет технические client/spring/version tags со стабильным префиксом и lifecycle consumer. В них нет employee/org/conversation/event/operation IDs, URL, endpoint, свободного текста ошибок или provider message ID.

## 3. Локальная диагностика браузера

В консоли аутентифицированной вкладки:

```js
window.clientbusNotificationDiagnostics?.()
```

Снимок содержит `version`, `managedPolling`, состояние и числа transport `attempts/reconnects/resyncs`, recovery `mode/rounds/failures`, счётчики `reconciliation` и `requests`. `requests=null` означает выключенный новый HTTP budget. Reconnects равны числу попыток соединения после первой; это не число успешно восстановленных соединений. Resyncs учитывают сигналы транспортного callback, а не каждый периодический REST refresh.

Значения копируются и замораживаются. IDs, токены, тексты, URLs и endpoints не выдаются; данные никуда не отправляются. Смена account/workspace, logout и cleanup отзывают прежнюю функцию. Сохранённая оператором ссылка на старую функцию возвращает `null`; непрошедший scope guard не может затем ожить при возврате старого scope. Отсутствие provider означает отсутствие глобального accessor. Счётчики начинают новый жизненный цикл вместе с новым scope и не агрегируют вкладки.

В отчёт об ошибке допустимо приложить этот снимок и номер версии bundle. Не прикладывать Authorization, содержимое storage, HAR без очистки, тексты сообщений или DOM с персональными данными. Обновление UI и его задержку проверяют браузерные сценарии 11.12, а не серверный socket timer.

## 4. Корреляция логов

`NotificationDiagnostics` принимает фиксированные stage/outcome/code и необязательные UUID. Идентификатор ошибки в `NotificationApiErrorDto.correlationId` совпадает с `correlationId` серверной записи API. Его ищут вместе с временем и экземпляром. Для принятой History-операции далее используют `operationId`, а для outbox/Kafka — доступные `eventId` и `correlationId`. Для push поля называются `notificationId` и `deliveryId`. У каждого этапа не обязаны одновременно существовать все идентификаторы.

Формат виден в обычном текстовом log и доступен как structured key/value. Неизвестный внутренний код превращается в `INTERNAL_ERROR`; исключение, его message/stack, payload, Installation Secret и endpoints через helper не передаются. Глобальный MDC и новая система distributed tracing не введены; асинхронные задачи передают доступные UUID явно. Идентификаторы не следует переносить в метки метрик.

## 5. Включение и возврат флагов

Префикс BE-параметров — `clientbus.notifications`. Изменение BE-флагов требует перезапуска процесса; автоматического hot reload нет. UI `VITE_*` задаются при сборке и требуют выпуска нового bundle.

| Флаг | Текущая конфигурация/ default | При выключении |
|---|---|---|
| `creation-enabled` | В `application.yml` true; binding default false | Новые персональные уведомления не создаются. Уже сохранённые читаются, могут доставляться и очищаются; доменные записи не отменяются |
| `history-submission-enabled` | В `application.yml` true; binding default false | Новые History jobs не принимаются. Статус, discovery, допустимый idempotent replay и выполнение ранее принятых операций сохраняются |
| `sse-enabled` | Binding default false; в основном application отдельно не включён | Нет SSE gateway/subscriber и UI relay. `UiOutboxWriter.messageSaved` пропускает создание message invalidations; History и центр сохраняют свои UI события в outbox, который может накапливаться |
| `push-enabled` | Binding default false; отдельно не включён | Provider worker не выполняет доставку. Сохранённые intents и registrations не удаляются флагом; persistence cleanup продолжает работать. При повторном включении учитываются TTL, права и актуальная binding |
| `history-consumer-enabled` | true по умолчанию | Не стартует Kafka listener History. Это не pause worker: watchdog/poll может находить и исполнять допустимые принятые операции |
| `VITE_UI_RECOVERY_ENABLED` | true | false возвращает прежние message/metadata timers, выключает новый scheduler/HTTP budget; SSE и lifecycle-защита сохраняются |
| `VITE_HISTORY_OPERATIONS_ENABLED` | true | false направляет новые загрузки в legacy API; уже сохранённые ключи/operation IDs продолжают восстанавливаться через operation API. Unknown outcome принятой команды не переключается автоматически на legacy |

SSE off не является единым выключателем записи всех invalidations. При длительном отключении надо контролировать рост UI outbox и срок/ёмкость хранения. Пропущенные message invalidations не достраиваются задним числом; состояние после возврата сверяется REST. Выключенный push не означает, что в БД обязательно нет новых intents. Наличие backlog сопоставляют с флагами экземпляров, сроками и ожидаемой нагрузкой.

Порядок включения:

1. Проверить совместимые версии UI/BE, схему, SQL/connection timeouts, уникальные gateway instance IDs, management access и свежесть метрик при выключенной доставке.
2. Зафиксировать исходные backlog/возраст/карантин и тестируемые сценарии. Проверить REST и polling/fallback до включения SSE.
3. Включить требуемый transport в выбранном окружении, перезапустить BE и убедиться в readiness и отдельном delivery health. При push сначала требуется готовый adapter и проверенная allowlist/конфигурация секретов.
4. Проверить создание→outbox→публикацию→REST reconciliation, reconnect, отключение/возврат Kafka и отсутствие роста физических очередей.
5. При проблеме остановить создание новых jobs/уведомлений или transport соответствующим флагом, сохранив принятые данные и REST. Проверить накопление backlog; после устранения причины включить и проверить его обработку. Не обнулять статусы и claim versions вручную.

Откат флагом относится к той же совместимой версии приложения. Возврат старого бинарника или схемы требует отдельной проверки DTO, SSE version, новых статусов и idempotency/retention правил. Наличие `ddl-auto=update` не обеспечивает downgrade или преобразование старых имён таблиц.

## 6. Очистка и восстановление

Основная cleanup выполняется bounded batch 100 каждые 60 секунд по default `cleanup-poll-ms`, независимо от creation/SSE/push. Она учитывает ссылки между notification, delivery и dedup, сроки command и опубликованного outbox. History-команды с `operationid` принадлежат retention History и не удаляются общей очисткой только по возрасту публикации. Их правила и ручной retry описаны в [NOTIFICATION_HISTORY.md](NOTIFICATION_HISTORY.md).

Базовые значения retention: источник replay — 30 дней, dedup — 90 дней, центр — 30 дней, command idempotency — 7 дней, History idempotency/retention — не менее 7 дней, terminal delivery и published outbox — 7 дней, активность installation — 30 дней. Alert TTL задаётся отдельно и короче retention. Сохранённый `retainuntil` и ссылки имеют приоритет над предположением «старше default — можно удалить». Не уменьшать dedup ниже replay window или History retention ниже обещанного idempotency window.

### Проверка без чтения приватных полей

Следующие PostgreSQL-запросы предназначены для разовой проверки состояния авторизованным оператором; это не новый частый poll и не API:

```sql
select destination, status, count(*) as records
from dbo.notificationoutbox
where status in ('PENDING', 'RETRY', 'IN_FLIGHT', 'QUARANTINED')
group by destination, status;

select status, count(*) as operations
from dbo.historysyncoperation
where status in ('PENDING', 'RUNNING')
group by status;

select status, count(*) as deliveries
from dbo.notificationdelivery
where status in ('PENDING', 'RETRY', 'IN_FLIGHT')
group by status;
```

Не выполнять `select *` по outbox/installations/delivery для вложения в отчёт: там могут быть payload и зашифрованные/служебные значения. Схема проекта — `dbo`; таблицы и явно заданные колонки имеют имена без `_`.

### Типовые отказы

| Наблюдение | Проверка и действие |
|---|---|
| Kafka недоступна | Сравнить readiness и delivery, флаги и свежий UI/History backlog. Восстановить брокер/ACL/topic, дождаться reconnect/relay retry, проверить REST reconciliation. Не удалять принятые операции ради пустого backlog |
| БД недоступна или pool исчерпан | Использовать внешнюю management identity: liveness и кешированные метрики доступны без пользовательской БД; readiness проверяет БД и может ждать connection timeout. Проверить sample valid/age, connection acquisition и JDBC/socket timeout, доступность pool. Последние gauges считать устаревшими. После восстановления дождаться новых снимков и lease/watchdog recovery |
| Растёт QUARANTINED | Найти безопасный code/eventId/operationId, исправить конфигурацию/topic/схему. Использовать существующий условный `UiOutboxStore.retryQuarantined` для UI либо `HistorySyncService.retryQuarantined` для History. Общедоступного HTTP retry нет; History retry допустим только для PENDING до deadline. Terminal не воскрешать, payload вручную не менять |
| History зависла или операции FAILED, но слоты заняты | Сопоставить logical active, physical active/slots/capacity и возраст maintenance. Неотменяемый `IChannelConnector` может держать слот после deadline. Устранить отказ коннектора; контролируемый перезапуск освобождает процесс, а lease/fencing сохраняют правила позднего результата. Telegram остаётся обычным `IChannelConnector` |
| Очереди SSE/overflow/reconnect растут | Проверить active/capacity writers, очереди, причины closed/resync, proxy buffering и finite socket write timeout. Увеличение лимита без профиля нагрузки не является исправлением. После закрытия браузер восстанавливается REST |
| Push retry/expiry растут | Сопоставить provider responses/waits с committed transitions и TTL. Проверить allowlist окружения, adapter и регистрацию. Поздний ACCEPTED после timeout не доказывает сохранённый успех или доставку устройству |
| Очистка перестала продвигаться | Проверить cleanup valid, last-success age и errors; для History — отдельный maintenance. Восстановить причину, дать bounded batches обработать накопление. Не удалять связанные строки или карантин произвольным DELETE |

Примеры сигналов для наблюдения: `sample.valid=0` после стартового ожидания; возраст снимка больше объявленного stale threshold; рост oldest/backlog при включённом обработчике; ненулевой/растущий карантин; занятые все физические worker slots при отсутствии прогресса; длительно неуспешный maintenance/cleanup; повторяющийся overflow. Накопление при явно выключенной доставке отмечается как пауза и риск ёмкости, а не автоматически как авария Kafka. Окна алертов, допустимые backlog и latency должны быть зафиксированы по измеренному профилю 11.12.

## 7. Gateway, proxy и остановка

Каждому одновременно работающему gateway нужен уникальный `gateway.instance-id`; группа — `clientbus-ui-{instanceId}`. Совместное использование ID вызывает rebalance и потерю требуемого fanout. Graceful stop удаляет только собственную неактивную группу при `cleanup-group-on-stop=true` с ограниченным broker timeout. После crash группа может остаться: оператор сначала подтверждает, что экземпляр выведен, а группа пуста/неактивна, затем удаляет только её точное имя административными средствами Kafka. Массовое удаление по префиксу и удаление группы живого экземпляра не являются восстановлением. Подробности — [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md).

Для `/api/events` отключить cache/buffering/compression, сохранить Authorization header и долгий HTTP response. Proxy read timeout должен превышать heartbeat с запасом; при heartbeat 15 секунд существующий пример — 60 секунд. Write/socket timeout должен быть конечным. `X-Accel-Buffering: no` не заменяет настройки всех proxy. Контейнерный write может физически удерживать writer после логического 5-секундного deadline; число потоков/очередей ограничено. Параметры и протокол — [NOTIFICATION_TRANSPORT.md](NOTIFICATION_TRANSPORT.md).

Останавливать приложение через штатное закрытие Spring context и корректный сигнал завершения. Stop прекращает новый захват/подачу задач, останавливает subscriber/SSE и прерывает executors. Ожидание consumer/брокера ограничено; History/push workers не ждут бесконечно завершения внешнего вызова. Принятые DB operations/outbox не превращаются массово в FAILED. Lease, deadline и claim version определяют допустимое продолжение после restart. `shutdownNow()` не гарантирует остановку неотменяемого внешнего вызова. Принудительное завершение IDE/ОС не обещает graceful cleanup, но не должно заменять транзакционное восстановление ручной правкой статусов.

Перед первым запуском новой версии сохранить резервную копию БД и обеспечить права Hibernate на требуемый schema update. В проекте используется `ddl-auto=update`, миграции этим этапом не вводятся. Это не обещание автоматического rename старых таблиц/колонок, переноса данных или отката схемы. Для уже существующей схемы оператор сверяет фактические имена с entity и SQL; переход между несовместимыми именами требует отдельного решения.

### Имя топика History Sync

При завершении 11.11 по решению пользователя убран суффикс версии из History topic и его DLQ: актуальные имена — `clientbus.history-sync-operations` и `clientbus.history-sync-operations.dlq`. Producer, consumer, topic beans, relay и метрики используют общую константу. Версия payload `schemaVersion=1` сохраняется.

Если локальная БД использовалась до переименования, перед запуском новой версии остановить все старые экземпляры BE и разово заменить сохранённое назначение команд:

```sql
begin;
update dbo.notificationoutbox
set destination = 'clientbus.history-sync-operations'
where destination = 'clientbus.history-sync-operations.v1'
  and operationid is not null;
commit;
```

Команда идемпотентна; меняется только destination, включая уже PUBLISHED строки. Payload, eventId, статусы, claimVersion и lease сохраняются. Это позволяет новому relay продолжить непубликованные команды, а DB recovery — восстановить PENDING с опубликованной командой. Перенос Kafka records не требуется; схема БД не меняется. Hibernate update не изменяет значения destination. Этот разовый SQL не выполняется приложением автоматически; пользовательская БД при разработке не изменялась. Старые Kafka topics/DLQ остаются до отдельной очистки и автоматически не удаляются.

## 8. Push: окружение и секреты

Реальные FCM/APNs adapters в текущую поставку не входят. Есть контракт `PushProvider`, foundation и тестовый provider. `push-enabled=true` требует корректного 32-byte Base64 `push.endpoint-key`, allowlist `(appId, provider, environment)` и подходящих adapters; один флаг не подключает production push. APNs допускается только для iOS. Секреты предоставляются внешней конфигурацией/секрет-хранилищем, не коммитятся в application.yml, логи и отчёты. Sandbox/production используют соответствующие приложения, credentials и регистрационные данные; смешивать их нельзя.

Endpoint зашифрован AES-GCM одним текущим ключом. Автоматического keyring/key ID/rotation нет: простая замена ключа делает прежние ciphertext нечитаемыми. При восстановлении БД нужен согласованный защищённый backup того же ключа. При утрате/компрометации ключа остановить provider delivery, согласовать замену и повторную регистрацию endpoints либо отдельную процедуру перешифрования; не обещать восстановление endpoint без исходного ключа и не печатать его для диагностики. Installation proof и auth token также являются секретами.

Очистка отключает неактивные installations, завершает истёкшие deliveries и удаляет только допустимые terminal/неиспользуемые строки. Native token refresh, получение ОС, открытие приложения и фактическое прочтение push не измеряются этой поставкой. Контракт клиентской регистрации, права и binding описаны в [NOTIFICATION_ACCESS.md](NOTIFICATION_ACCESS.md) и [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md).

## 9. Проверка перед 11.12

Проверить отдельно: management access без прав и с SUPER_ADMIN; внешнюю management identity при отказе БД и её изоляцию от бизнес-API; сбор снимков при SSE/push off; ошибка и устаревание DB sample; Kafka outage с доступным REST; прекращение новых claims при shutdown и восстановление принятых операций; логическое завершение с ещё занятым физическим слотом; фактические переходы retry→EXPIRED и устаревшего push claim; cleanup при выключенной доставке; отзыв диагностической функции UI при account/workspace/logout. Результаты запусков фиксируются после выполнения, а не выводятся из наличия метрик.

Для 11.12 заранее задать число пользователей/вкладок/диалогов, частоту событий и REST, объём активного/архивного outbox, число gateway и длительность outage. Затем согласовать числовые критерии p95, backlog, памяти/потоков/соединений и восстановления. До этих измерений автоматические пределы конфигурации не выдаются за подтверждённый эксплуатационный SLO.

## 10. Протокол 25.10.2026

11.11 закрыт по согласованному повторному ревью. Проверки выполнены на локальном стенде; полная приёмка 11.12 сохраняет отдельный нагрузочный профиль и критерии.

| Проверка | Результат / артефакт |
|---|---|
| Полный BE suite с дополнением 11.11.6 | **908/908**, без пропусков; `server/target/notification-11-11-avatar-all-tests.log`, BUILD SUCCESS 25.10.2026 21:02:45 +03 |
| Адресные регрессии management/SSE | **41/41**, включая отказ соединений БД, Basic isolation, JWT enabled/role, custom/root management paths и единственную причину закрытия SSE; `server/target/notification-11-11-review-fixes-targeted.log`. Эти тесты входят в полный BE suite |
| Адресные проверки аватара | **65/65**, включая полный Spring/DB flow, rollback, параллельное создание и afterCommit error; `server/target/notification-11-11-avatar-targeted.log`. Входят в полный BE suite |
| Полный UI suite | **351/351**; `ui/node_modules/.cache/notification-11-11-avatar-ui.log` |
| Browser History и центра на PostgreSQL/Kafka/BE | **12/12**: 10 History и 2 центра, desktop/mobile. `history-browser.log` и `notification-center-browser.log`; оба acceptance-класса прошли в `server/target/notification-11-11-avatar-browser.log` 21:15:35 +03. Общий запуск сообщил ошибку первоначальной новой avatar-фикстуры; она исправлена и проверена отдельно следующей строкой |
| Browser аватара с настоящим BE/БД/файловым хранилищем | **2/2**, desktop/mobile: preview → отмена без persistence → создание с большим фото → reload → повтор без лишнего файла. `server/target/notification-11-11-avatar-new-browser.log`, BUILD SUCCESS 21:31:07 +03; подробности `new-conversation-avatar-browser.log`, screenshots в `new-conversation-avatar-browser-results`. В фикстуре исправлены длина логина и адрес проверки существующего avatar endpoint; production-код после BE suite не менялся |
| TypeScript | Приложение, Vite config и E2E прошли; пользовательский `ui/tsconfig.app.tsbuildinfo` сохранён |
| Сборки | Production и отдельный rollback bundle с `VITE_UI_RECOVERY_ENABLED=false` и `VITE_HISTORY_OPERATIONS_ENABLED=false` прошли; основная production-сборка сохранена |
| UI resources | Все **45** файлов `ui/dist` побайтово совпадают с `server/target/classes/static`; текущий JS `index-C-NPMskG.js` |
| Стоимость SQL | Точный production-запрос на 20 000 PUBLISHED + 5 активных + 2 QUARANTINED; default planner выбрал индексное чтение семи строк. `server/target/notification-11-11-outbox-statistics-plan.txt`; без принудительных индексов и временного SLO |

Проверены rollback/commit и повтор команды, ошибка наблюдения после успешного commit, независимые ошибочные/устаревшие DB-снимки, сбор без SSE, физические зависшие слоты, реальная остановка Spring context и восстановление, provider timeout/busy/error/late result, фактический retry→EXPIRED, смена endpoint/binding, cleanup и management ACL. Native History binder проверен отдельно и на настоящем consumer; удаление consumer освобождает binder/meters. Browser дополнительно проверяет отсутствие приватных значений в локальном снимке и отзыв сохранённой функции после смены Workspace.

При приёмке исправлен management access-denied: прямой 403 предотвращает повторный ERROR dispatch с ложным 401. Исправлена недетерминированность старого теста финального импорта/watchdog: SKIP LOCKED допускает пропуск занятой строки, поэтому тест требует следующий штатный проход; отдельная детерминированная проверка подтверждает последующий DEADLINE_EXCEEDED. Business fencing/terminal rules сохранены.

По итогам двух независимых ревью устранена зависимость аварийной диагностики от пользовательской БД: отдельный management Basic использует только внешние credentials и ограничен health/metrics. HTTP-проверки при отказе DataSource подтверждают liveness 200, readiness 503, чтение сохранённых метрик и sample.valid=0; обычный JWT сохраняет обращение к БД и недоступен при её отказе. Проверены выключенный Basic по умолчанию, неверные credentials, отсутствие HTTP session, запрет бизнес-API, роль/disabled-пользователь и management base/context paths. Для deferred SSE resync сохраняются overflow/malformed; timeout/write error, наступившие раньше, учитываются единожды. Независимое повторное чтение исправлений подтверждённых дефектов не выявило.

При финальном переименовании History topic обнаружен старый недетерминированный `JwtServiceTest.modifiedToken_shouldReturnFalse`: замена последнего символа Base64URL иногда меняла только незначащие биты и сохраняла прежнюю подпись. Тест теперь изменяет байт декодированной подписи и кодирует его обратно; отдельно подтверждает валидность исходного токена. Production-проверка JWT не менялась. History topic/DLQ используют имена без суффикса версии; операционная процедура перехода приведена выше.

В основном application остаются `creation-enabled=true`, `history-submission-enabled=true`; SSE/push отдельно не включались. Новая конфигурация и классы требуют перезапуска BE, UI bundle уже собран. Реальные FCM/APNs/native open, production SLO и произвольный бинарный downgrade не заявляются. Миграций, staging и Git-коммитов нет.

Дополнение 11.11.6 — [аватары контактов при создании диалога](OUTBOUND_CONTACT_AVATARS.md). Preview в поиске остаётся временным, а по началу диалога BE независимо получает и сохраняет большое фото. Существующие права и общий IChannelConnector сохранены; схема БД и notification events не расширялись. Проверены отсутствие persistence при поиске, ошибки фото/storage, отзыв доступа, повтор и гонки создания, cleanup при rollback и сохранение referenced файла после commit. Production и rollback bundles собраны заново с сохранением пользовательского tsbuildinfo.
