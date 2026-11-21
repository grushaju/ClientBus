# Stage 11.5 — первый вертикальный E2E и надёжная публикация

Выполнен 22.10.2026 по согласованному пользователем плану. Контракты: [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md); транспорт: [NOTIFICATION_TRANSPORT.md](NOTIFICATION_TRANSPORT.md).

## Рабочий путь

Входящий PlatformMessageRequest → MessageProcessingService → MessageService → сообщение, состояние диалога, вложения и notificationoutbox в общей бизнес-транзакции → relay → clientbus.ui.invalidations.v1 → каждый SSE gateway → UiEventsProvider → REST Inbox/переписки.

При первой вставке сообщения сохраняются два минимальных события: messages.invalidated и inbox.invalidated с conversation resource и текущим origin. В Kafka/SSE не передаются текст сообщения и вложения. Gateway повторно проверяет текущий ACL, UI получает содержимое из обычного REST. Повтор того же externalId возвращает существующее сообщение и не создаёт новые outbox records. При rollback откатываются сообщение и outbox; внешнего publish внутри бизнес-транзакции нет.

Writer включается существующим clientbus.notifications.sse-enabled=true. Этот флаг также включает transport и relay; default=false. Остальные creation/push/history flags остаются независимыми. Notification center и персональные alert intents здесь не создаются.

## Relay и устойчивость

- UiOutboxStore захватывает по одной due записи короткой транзакцией: FOR UPDATE SKIP LOCKED → IN_FLIGHT, attempts/claimversion увеличиваются, leaseuntil задаётся DB clock. Захват одного задания непосредственно перед отправкой не расходует lease большого batch в ожидании предыдущих ack.
- KafkaUiOutboxPublisher — отдельный String/String producer с общими TLS/SASL настройками KafkaProperties, acks=all и idempotence. На UI topic не попадают business serializer/type headers. max.block=2 секунды, request timeout=5 секунд, delivery timeout/ожидание ack=10 секунд. Приём брокером не означает чтение пользователем.
- Вызов Kafka выполняется после commit claim. PUBLISHED/publishedat устанавливаются только после ack и только для совпавших id/claimVersion/IN_FLIGHT. Просроченный IN_FLIGHT может получить новый claim; поздний результат прежнего владельца не перезаписывает новый.
- Timeout/неоднозначный сетевой результат → RETRY с exponential backoff 1–256 секунд и прежними eventId/payload. Пропажа процесса после Kafka ack до DB ack допускает повтор той же записи. Это at-least-once публикация; UI invalidation безопасна при повторе.
- Невалидный/несовместимый payload, неверные envelope identity, неподдерживаемый destination/operation, постоянная ошибка serialization/authorization/configuration/topic/size → QUARANTINED. Такая запись не выбирается обычным poll и не блокирует следующую. Сохраняется безопасный errorcode, тела и credentials не логируются.
- Нынешний dispatcher обслуживает UI invalidation. History command routing и проверка terminal outcome/deadline добавляются совместно в 11.10; команды нельзя включать через этот частичный relay.
- У relay выделенный scheduler uiOutboxScheduler с одним потоком. После ревью явно создан отдельный default taskScheduler для unqualified бизнес-задач и uiOutboxMetricsScheduler для статистики; Recent Chats не использует поток relay. outbox-poll-ms=500 задаёт fixed delay после прохода; outboxBatchSize=100 ограничивает число отправок за проход. lease по общим контрактам — 30 секунд. SQL-переходы обеспечивают fencing также между несколькими процессами relay.

Метрики Micrometer: clientbus.notification.outbox.pending, .quarantined, .oldest.seconds, .attempts с ограниченным result=published/retry/quarantined, .errors со stage=database. Gauges обновляются отдельным sampler каждые 2 секунды (outbox-metrics-poll-ms), а не выполняют SQL при scrape и не ждут Kafka batch. До первого снимка значения NaN; sample.valid отражает результат последней завершённой попытки, sample.age.seconds растёт от последнего успешного снимка. Возраст старейшей pending записи также растёт между снимками. При ошибке БД сохраняется последний снимок с valid=0 и продолжающим расти возрастом; свежесть проверяется по age, в том числе если SQL sampler завис. pending/oldest учитывают PENDING/RETRY/IN_FLIGHT; карантин наблюдается отдельно. uiPipeline продолжает отражать Kafka-подписку, а не успешность outbox relay. Дашборды/алерты/retention cleanup относятся к 11.11; неопубликованные и карантинные записи по published retention не удаляются.

Ручной повтор реализован как UiOutboxStore.retryQuarantined(id): условный QUARANTINED → RETRY только для UI topic и operationid IS NULL. eventId, payload и первоначальный смысл не изменяются; PUBLISHED не повторяется. Общедоступного HTTP endpoint этой операции нет. Оператор сначала устраняет причину ошибки; некорректный payload при простом повторе снова попадёт в карантин. Редактирование payload произвольным UPDATE не является штатной процедурой retry.

## Origin

PlatformMessageRequest получил origin и withOrigin; Kafka payload переносит его до бизнес-транзакции и outbox. Входящий realtime listener помечает LIVE. Оба пути KafkaSyncConversationHistoryConsumer — фоновая публикация и синхронное сохранение — принудительно задают HISTORY_IMPORT независимо от результата connector. Recent Chats запускает загрузку сообщений через этот же history consumer, поэтому импортированная история также получает HISTORY_IMPORT. Сам snapshot Recent Chats пока не подключён к новому каталогу событий: его расширение выполняется вместе с остальными writers.

Legacy payload/старый Java constructor без origin получают SYNC, что не даёт им случайно приобрести LIVE alert semantics. LIVE при JSON roundtrip сохраняется. Сообщения импортов могут обновлять REST-представления, но персональных N2/toast здесь нет. Отдельная дедупликация права LIVE-уведомления после более раннего импорта, frozen audience и финальная проверка всех путей остаются обязательными до включения N2 в 11.8; message existed не будет её заменой.

Telegram использует существующий generic publisher/connector flow. Добавлен только provenance источника, без новых notification scopes, capabilities или маршрута владельца connector.

## UI

UiEventsProvider размещён внутри authenticated ProtectedLayout вокруг AppLayout. Один transport соответствует текущим employee/token/workspace; cleanup останавливает старый transport и подписки. Feature-disabled 404 завершает попытку без бесконечного retry. stream.ready запускает сверку смонтированных ресурсов; reconnect повторяет её.

InvalidationCoordinator объединяет сигналы с задержкой 100 мс и минимальным интервалом 250 мс, допускает один refresh подписки одновременно. Сигнал во время REST повышает требуемую ревизию для следующего запроса; разрешённый текущий ответ не отменяется. Ready ждёт чтение, покрывающее ревизию своей сверки: успешный ответ более раннего запроса не завершает её преждевременно, а непрерывный поток более поздних событий не заставляет ready ждать бесконечно. Временная ошибка сохраняет dirty и повторяется через секунду; подтверждённые Inbox 403/404 очищают недоступные данные и не создают retry loop. Unsubscribe отменяет scope signal и таймеры; lifecycle допускает StrictMode cleanup/setup. При document.hidden автоматические refetch откладываются до возврата вкладки.

Inbox перечитывает прежнюю REST-модель поиска/карточек, сохраняя смонтированные строки при refresh. Переписка использует существующий refreshMessages с дочитыванием до старой границы и локальными версиями send/retry. Сигналы не заменяют бизнес-состояние произвольными DTO. Начальная загрузка/загрузка старой страницы завершаются перед signal refresh; ошибки refresh доступны механизму retry.

Это минимальные две подписки для вертикали. Общая ревизия lifecycle/scope cancellation всех запросов и подписок, строгая проверка непрерывного потока, остальные ресурсы — 11.6; полноценный fallback polling, редкая контрольная сверка и эксплуатационные пределы — 11.7. Существующий механизм refresh сообщений этапа 10 сохраняется.

## Проверка

- UiOutboxIntegrationTest: PostgreSQL rollback без сообщения/outbox, repeat без новых eventId, реальная публикация после commit, takeover/fencing позднего claim, ручной retry со стабильной identity, poison payload без блокировки следующего, конкурентные claim, persisted origin без создания notification center.
- UiOutboxRelayTest: неоднозначный ack timeout не даёт false published, повтор использует тот же payload/eventId, постоянная authorization error уходит в карантин.
- Origin regressions: LIVE listener, оба history paths, MessageProcessingService propagation, legacy/explicit JSON roundtrip.
- NotificationBrowserAcceptance: настоящий HTTP/JWT/PostgreSQL/Kafka/SSE/production React в desktop и mobile Chromium. Две разрешённые сессии обновляют переписку и карточку Inbox; запрещённая сессия не получает messages.invalidated и REST возвращает 403; rollback сохраняет исходные counts/eventIds; повтор не создаёт новые задания; разрыв SSE и новый ready восстанавливают последующие обновления. Наблюдаются реальные stream bytes, бизнес-ответы не подменяются. Test-only HTTP controls исключены из application component scan.
- Coordinator/transport tests: coalescing во время REST без параллельного fetch, dirty/retry после отказа, отмена подписки, hidden/resume, StrictMode lifecycle, выключенный endpoint.

Итог по успешным целевым прогонам этого этапа: 133 JUnit-теста (включая один browser harness), 196 UI-тестов; browser harness выполнил 2 сценария desktop/mobile. Дополнительно основной mock browser-набор Inbox: 28 сценариев прошли, включая desktop/mobile и выключенный transport. TypeScript приложения (--incremental false) и E2E, Vite production build прошли; git diff --check чистый. Это целевые наборы, а не полный запуск всех BE-тестов репозитория. Новых миграций и Git-коммитов нет.

Повтор browser acceptance после проверки TypeScript и Vite build:

```powershell
mvn -o -pl server -am '-Dtest=NotificationBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' test-compile surefire:test
```

Используются отдельные PostgreSQL/Kafka контейнеры AbstractIntegrationTest, реальная рабочая БД и подключённый внешний канал не нужны. Не запускать параллельно с другими Maven integration runs из-за общих test container names/порта 15432. Временный manifest с тестовыми credentials удаляется после harness; trace выключен. Лог результата: server/target/notification-browser.log.

11.5 завершён. План 11.6 согласован пользователем и реализован; протокол — NOTIFICATION_UI.md.

Финальная правка классификации Inbox 403/404 проверена отдельным прогоном 81 UI-теста Inbox/coordinator; TypeScript и production build повторно прошли. Финальная сборка UI скопирована в server static resources.

## Исправления ревью 11.5

Четыре замечания исправлены в рамках текущего этапа, без перехода к 11.6:

1. requestScope.withCancellation связывает deadline/AbortSignal попытки SSE со scope ресурса и REST, ограничивая всю операцию вместе с response.json. Coordinator освобождает выполняющийся запрос при отмене/дедлайне, даже если адаптер не реагирует на abort. Чтения Inbox, channels/accounts, страниц сообщений и отдельных статусов принимают signal. Initial/older/refresh переписки отслеживают свои контроллеры; отмена освобождает pending flags, поздний ответ отменённой операции не применяется. Обычная invalidation по-прежнему не отменяет разрешённый текущий ответ. Дедлайн read API/ресурса — 30 секунд; legacy history wait — 35 секунд на запрос, но явная отмена/дедлайн ready прерывает ожидание раньше. Отмена HTTP-ожидания не откатывает уже начатую BE history-команду; сохраняемая operation остаётся задачей 11.10.
2. Inbox при уже идущем initial fetch последовательно ожидает его и новое чтение после ready. Promise нового чтения возвращается coordinator, а его временная ошибка сохраняет потребность в повторе. Отдельного скрытого fire-and-forget повторного load больше нет. Scope change/unmount отменяет текущий запрос, generation guards сохраняются.
3. Default taskScheduler для фоновых бизнес-задач, uiOutboxScheduler и uiOutboxMetricsScheduler имеют разные executors. Spring default routing проверен настоящим TaskSchedulerRouter при заблокированном relay; одновременно выполняются unqualified business job и metrics task.
4. Статистика вынесена в UiOutboxMetrics, с независимым sampling, явными unknown/valid/freshness значениями. Проверено получение снимка очереди во время заблокированного Kafka publish; ошибка БД не превращает старые данные в свежий ноль.

Регрессии: abort + новый REST после reconnect, некооперативный зависший адаптер и deadline, ready на фоне старого fetch, initial Inbox + failed follow-up + retry, отменённый initial/refresh и поздний ответ, отмена во время JSON body, изоляция scheduler, независимое измерение метрик и видимая ошибка/давность снимка.

Проверки исправлений: полный UI suite 203 passed, после финальной защиты late response — целевой набор 57 passed; TypeScript и Vite build прошли. Целевой BE-прогон 16 passed включает outbox, metrics, scheduler isolation, реальные transport integration и browser harness. Desktop/mobile real E2E — 2 passed. Предыдущие числа выше относятся к первоначальному завершению 11.5, а не к повторному полному запуску всех BE-тестов. Миграций и коммитов нет; 11.6 согласован и реализован; протокол — NOTIFICATION_UI.md.